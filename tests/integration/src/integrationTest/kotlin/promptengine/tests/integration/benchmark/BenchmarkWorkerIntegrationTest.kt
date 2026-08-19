package promptengine.tests.integration.benchmark

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import promptengine.domain.benchmark.Benchmark
import promptengine.domain.benchmark.BenchmarkMetricType
import promptengine.domain.benchmark.BenchmarkStatus
import promptengine.domain.benchmark.BenchmarkTarget
import promptengine.domain.benchmark.GoldenDataset
import promptengine.domain.benchmark.GoldenDatasetItem
import promptengine.domain.event.EventContext
import promptengine.domain.execution.ExecutionAdapter
import promptengine.domain.optimization.ModelProfile
import promptengine.domain.pipeline.PipelineContext
import promptengine.domain.pipeline.PipelineMode
import promptengine.domain.pipeline.PipelineRequest
import promptengine.domain.pipeline.PipelineRunner
import promptengine.domain.prompt.NewPromptVersion
import promptengine.domain.prompt.Prompt
import promptengine.domain.prompt.PromptContent
import promptengine.domain.prompt.PromptKey
import promptengine.domain.render.MessageRole
import promptengine.domain.render.ModelHints
import promptengine.domain.render.OutputFormat
import promptengine.domain.render.RenderedMessage
import promptengine.domain.render.RenderedPrompt
import promptengine.domain.shared.Cost
import promptengine.domain.shared.SemVer
import promptengine.domain.shared.TokenCount
import promptengine.engine.benchmark.ConsistencyScoringRule
import promptengine.engine.benchmark.DeterminismScoringRule
import promptengine.engine.benchmark.NormalizedExactMatchScoringRule
import promptengine.engine.execution.ExecutionCoordinator
import promptengine.engine.formatter.TextOutputFormatter
import promptengine.infrastructure.benchmark.BenchmarkWorker
import promptengine.infrastructure.persistence.EventStorePromptRepository
import promptengine.infrastructure.persistence.JdbcBenchmarkItemResultRepository
import promptengine.infrastructure.persistence.JdbcBenchmarkRepository
import promptengine.infrastructure.persistence.JdbcGoldenDatasetRepository
import promptengine.plugin.execution.fake.FakeExecutionAdapter
import promptengine.plugin.execution.fake.FakeExecutionScenario
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * [BenchmarkWorker]のTestcontainers(PostgreSQL 16)統合テスト（ADR-0035決定3・決定5、
 * フェーズ(c)）。`docs/prompts/m2-4b-c.md`「■ 特に検証すること」の1〜4に対応する。
 *
 * Load/Merge/Import/ResolveVariables/ResolveContext/Validation/Optimizationステージは
 * このテストの関心事ではない（Benchmarkワーカー自身の振る舞い——Claim・N回実行・採点・
 * 状態遷移——を検証する対象であり、それらのステージの正しさは他のテストが担保する）ため、
 * [PipelineRunner]は render+execute のみを行う軽量な実装（[FakePipelineRunner]）で代替する。
 * `RenderedPrompt`の構築・`ExecutionCoordinator`・`FakeExecutionAdapter`は実クラスをそのまま使う
 * （BenchmarkWorkerが実際に触れる境界——`PipelineRequest.modelHints`→`RenderedPrompt.modelHints`→
 * `ExecutionAdapter.execute`——は本物の経路で検証する）。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BenchmarkWorkerIntegrationTest {
    private lateinit var dataSource: DataSource
    private lateinit var jdbcTemplate: NamedParameterJdbcTemplate
    private lateinit var promptRepository: EventStorePromptRepository
    private lateinit var datasetRepository: JdbcGoldenDatasetRepository
    private lateinit var benchmarkRepository: JdbcBenchmarkRepository
    private lateinit var itemResultRepository: JdbcBenchmarkItemResultRepository

    private val context =
        EventContext(actor = "user:owner", traceId = "trace-1", occurredAt = Instant.parse("2026-01-01T00:00:00Z"))
    private val modelProfile =
        ModelProfile(
            maxContextTokens = TokenCount(1_000),
            tokenizerId = "test-tokenizer",
            costPerToken = Cost(BigDecimal.ZERO),
        )
    private val scoringRules =
        listOf(NormalizedExactMatchScoringRule(), ConsistencyScoringRule(), DeterminismScoringRule())

    @BeforeAll
    fun setUp() {
        val hikariConfig =
            HikariConfig().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                driverClassName = postgres.driverClassName
            }
        dataSource = HikariDataSource(hikariConfig)
        Flyway.configure().dataSource(dataSource).load().migrate()

        jdbcTemplate = NamedParameterJdbcTemplate(dataSource)
        val transactionTemplate = TransactionTemplate(DataSourceTransactionManager(dataSource))
        promptRepository = EventStorePromptRepository(jdbcTemplate, transactionTemplate, jacksonObjectMapper())
        datasetRepository = JdbcGoldenDatasetRepository(jdbcTemplate, transactionTemplate, jacksonObjectMapper())
        benchmarkRepository = JdbcBenchmarkRepository(jdbcTemplate, transactionTemplate)
        itemResultRepository = JdbcBenchmarkItemResultRepository(jdbcTemplate, transactionTemplate)
    }

    @AfterAll
    fun tearDown() {
        (dataSource as HikariDataSource).close()
    }

    /**
     * `@TestInstance(PER_CLASS)`でDBを全テスト間で共有する（コンテナ起動コストを1回に抑える、
     * `JdbcBenchmarkRepositoryIntegrationTest`と同じ理由）ため、Benchmark関連テーブルは
     * 各テスト開始前に空にする（`onlyItemResultRow()`等、単一行を前提にしたクエリの
     * 決定性を保つため）。Prompt/PromptVersionは各テストが`UUID.randomUUID()`で
     * 固有のキーを使うため、クリアしない。
     */
    @BeforeEach
    fun cleanBenchmarkTables() {
        jdbcTemplate.update(
            """
            TRUNCATE TABLE benchmark_item_results, benchmark_targets, benchmark_metrics, benchmarks,
                golden_dataset_items, golden_datasets
            """.trimIndent(),
            MapSqlParameterSource(),
        )
    }

    private fun createApprovedPrompt(): PromptKey {
        val key = PromptKey("integration-test/${UUID.randomUUID()}")
        val semVer = SemVer(1, 0, 0)
        val (created, event) = Prompt.create(key, NewPromptVersion(semVer, PromptContent("body")), context)
        promptRepository.save(created, listOf(event))
        var prompt = promptRepository.findByKey(key)!!.submitForReview(semVer, validationPassed = true)
        promptRepository.save(prompt)
        prompt = promptRepository.findByKey(key)!!.approve(semVer, approvalCount = 1, requiredApprovalCount = 1)
        promptRepository.save(prompt)
        return key
    }

    private fun createDatasetWithItems(
        promptKey: PromptKey,
        itemCount: Int,
    ): UUID {
        val items =
            (1..itemCount).map {
                GoldenDatasetItem(
                    itemId = UUID.randomUUID(),
                    parameters = mapOf("index" to it),
                    // localeはnull（未設定シナリオを模す）: BenchmarkWorker.filterNonNullが
                    // contextDataの各スコープからnull値を除去することを実行経路で確認する。
                    context = mapOf("user" to mapOf("locale" to "ja-JP", "region" to null)),
                    expectedOutput = "expected-$it",
                )
            }
        val dataset = GoldenDataset.create(promptKey, "dataset", null, items)
        datasetRepository.save(dataset)
        return dataset.datasetId
    }

    private fun worker(
        executionAdapter: ExecutionAdapter,
        batchSize: Int = 20,
        claimTimeout: Duration = Duration.ofSeconds(30),
        instanceId: String = "instance-${UUID.randomUUID()}",
    ) = BenchmarkWorker(
        benchmarkRepository,
        datasetRepository,
        itemResultRepository,
        FakePipelineRunner(executionAdapter),
        scoringRules,
        modelProfile,
        instanceId,
        claimTimeout,
        batchSize,
        executionTimeoutMs = 5_000,
    )

    /** [PipelineRunner]のKDoc参照。render+executeのみ行う軽量実装。 */
    private class FakePipelineRunner(private val executionAdapter: ExecutionAdapter) : PipelineRunner {
        val requestedModelHints = CopyOnWriteArrayList<ModelHints?>()

        override fun run(
            request: PipelineRequest,
            mode: PipelineMode,
            traceId: String,
        ): PipelineContext {
            requestedModelHints += request.modelHints
            val rendered =
                RenderedPrompt(
                    messages = listOf(RenderedMessage(MessageRole.USER, "item")),
                    outputFormat = OutputFormat.TEXT,
                    tokenEstimate = TokenCount(1),
                    renderHash = "test-hash",
                    modelHints = request.modelHints,
                )
            val coordinator =
                ExecutionCoordinator(
                    executionAdapter,
                    mapOf(OutputFormat.TEXT to TextOutputFormatter()),
                    TEST_TOKENIZER,
                )
            val outcome = coordinator.run(rendered, request.executionPolicy!!, schema = null, budget = request.budget)
            return PipelineContext(
                request = request,
                mode = mode,
                traceId = traceId,
                rendered = rendered,
                executionOutcome = outcome,
            )
        }
    }

    @Test
    fun `PendingのBenchmarkはRunningへ遷移し全項目実行後にCompletedへ遷移する`() {
        val promptKey = createApprovedPrompt()
        val datasetId = createDatasetWithItems(promptKey, itemCount = 2)
        val target = BenchmarkTarget(UUID.randomUUID(), SemVer(1, 0, 0))
        val benchmark =
            Benchmark.create(
                promptKey,
                datasetId,
                listOf(target),
                setOf(BenchmarkMetricType.Accuracy),
                nRepetitions = 3,
            )
        benchmarkRepository.save(benchmark)
        val scenario = FakeExecutionScenario.Cycling(listOf("expected-1"), usage(), latency())
        val adapter = FakeExecutionAdapter(scenario)

        worker(adapter).runOnce()

        benchmarkRepository.findById(benchmark.benchmarkId)!!.status shouldBe BenchmarkStatus.Completed
        // estimatedExecutionCount（datasetSize=2）とワーカーの実行回数が一致すること。
        benchmark.estimatedExecutionCount(datasetSize = 2) shouldBe 2 * 1 * 3
        scenario.invocationCount() shouldBe 6
    }

    @Test
    fun `Nの設定値どおりに1項目あたりN回実行する`() {
        val promptKey = createApprovedPrompt()
        val datasetId = createDatasetWithItems(promptKey, itemCount = 1)
        val target = BenchmarkTarget(UUID.randomUUID(), SemVer(1, 0, 0))
        val n = 5
        val benchmark =
            Benchmark.create(
                promptKey,
                datasetId,
                listOf(target),
                setOf(BenchmarkMetricType.Accuracy),
                nRepetitions = n,
            )
        benchmarkRepository.save(benchmark)
        val scenario = FakeExecutionScenario.Cycling(listOf("expected-1"), usage(), latency())

        worker(FakeExecutionAdapter(scenario)).runOnce()

        scenario.invocationCount() shouldBe n
    }

    @Test
    fun `Consistencyは出力が完全一致すればスコア1_0`() {
        val promptKey = createApprovedPrompt()
        val datasetId = createDatasetWithItems(promptKey, itemCount = 1)
        val target = BenchmarkTarget(UUID.randomUUID(), SemVer(1, 0, 0))
        val benchmark =
            Benchmark.create(
                promptKey,
                datasetId,
                listOf(target),
                setOf(BenchmarkMetricType.Consistency),
                nRepetitions = 3,
            )
        benchmarkRepository.save(benchmark)
        val scenario = FakeExecutionScenario.Cycling(listOf("same-output"), usage(), latency())

        worker(FakeExecutionAdapter(scenario)).runOnce()

        val row = onlyItemResultRow()
        (row["consistency_score"] as BigDecimal).compareTo(BigDecimal.ONE) shouldBe 0
    }

    @Test
    fun `Consistencyは出力がばらつけばスコアが1_0未満になる`() {
        val promptKey = createApprovedPrompt()
        val datasetId = createDatasetWithItems(promptKey, itemCount = 1)
        val target = BenchmarkTarget(UUID.randomUUID(), SemVer(1, 0, 0))
        val benchmark =
            Benchmark.create(
                promptKey,
                datasetId,
                listOf(target),
                setOf(BenchmarkMetricType.Consistency),
                nRepetitions = 3,
            )
        benchmarkRepository.save(benchmark)
        val scenario = FakeExecutionScenario.Cycling(listOf("a", "b", "c"), usage(), latency())

        worker(FakeExecutionAdapter(scenario)).runOnce()

        val row = onlyItemResultRow()
        ((row["consistency_score"] as BigDecimal) < BigDecimal.ONE) shouldBe true
    }

    @Test
    fun `Determinismを要求するとN回実行すべてtemperature0_0で行われる`() {
        val promptKey = createApprovedPrompt()
        val datasetId = createDatasetWithItems(promptKey, itemCount = 1)
        val target = BenchmarkTarget(UUID.randomUUID(), SemVer(1, 0, 0))
        val benchmark =
            Benchmark.create(
                promptKey,
                datasetId,
                listOf(target),
                setOf(BenchmarkMetricType.Determinism),
                nRepetitions = 3,
                temperature = null,
            )
        benchmarkRepository.save(benchmark)
        val scenario = FakeExecutionScenario.Cycling(listOf("same-output"), usage(), latency())
        val pipelineRunner = FakePipelineRunner(FakeExecutionAdapter(scenario))
        val determinismWorker =
            BenchmarkWorker(
                benchmarkRepository,
                datasetRepository,
                itemResultRepository,
                pipelineRunner,
                scoringRules,
                modelProfile,
                "instance-determinism",
                Duration.ofSeconds(30),
                20,
                5_000,
            )

        determinismWorker.runOnce()

        pipelineRunner.requestedModelHints.size shouldBe 3
        pipelineRunner.requestedModelHints.all { it == ModelHints(temperature = 0.0) } shouldBe true
        val row = onlyItemResultRow()
        (row["determinism_score"] as BigDecimal).compareTo(BigDecimal.ONE) shouldBe 0
    }

    @Test
    fun `中断要求後は未Claimの項目を新たにClaimせずCancelledへ遷移する`() {
        val promptKey = createApprovedPrompt()
        val datasetId = createDatasetWithItems(promptKey, itemCount = 3)
        val target = BenchmarkTarget(UUID.randomUUID(), SemVer(1, 0, 0))
        val benchmark =
            Benchmark.create(
                promptKey,
                datasetId,
                listOf(target),
                setOf(BenchmarkMetricType.Accuracy),
                nRepetitions = 1,
            )
        benchmarkRepository.save(benchmark)
        val scenario = FakeExecutionScenario.Cycling(listOf("expected-1"), usage(), latency())
        // batchSize=1: 1回のrunOnce()で1項目だけ処理されるようにし、中断のタイミングを制御する。
        val pacedWorker = worker(FakeExecutionAdapter(scenario), batchSize = 1)

        pacedWorker.runOnce()
        val running = benchmarkRepository.findById(benchmark.benchmarkId)!!
        running.status shouldBe BenchmarkStatus.Running

        benchmarkRepository.save(running.requestCancellation())
        pacedWorker.runOnce()

        val cancelled = benchmarkRepository.findById(benchmark.benchmarkId)!!
        cancelled.status shouldBe BenchmarkStatus.Cancelled
        // 1件のみ実行され、残り2件は未Claimのまま（中途半端な状態が残らない: Benchmark自体は
        // 終端状態Cancelledに達しており、未実行項目はPendingのまま安全に放置される）。
        scenario.invocationCount() shouldBe 1
        val statuses =
            jdbcTemplate.query(
                """
                SELECT r.status FROM benchmark_item_results r
                JOIN benchmark_targets t ON t.target_id = r.target_id
                WHERE t.benchmark_id = :benchmarkId
                """.trimIndent(),
                MapSqlParameterSource("benchmarkId", benchmark.benchmarkId),
            ) { rs, _ -> rs.getString("status") }
        statuses.count { it == "Completed" } shouldBe 1
        statuses.count { it == "Pending" } shouldBe 2
    }

    @Test
    fun `再起動しても完了済み項目は再実行されない`() {
        val promptKey = createApprovedPrompt()
        val datasetId = createDatasetWithItems(promptKey, itemCount = 2)
        val target = BenchmarkTarget(UUID.randomUUID(), SemVer(1, 0, 0))
        val benchmark =
            Benchmark.create(
                promptKey,
                datasetId,
                listOf(target),
                setOf(BenchmarkMetricType.Accuracy),
                nRepetitions = 1,
            )
        benchmarkRepository.save(benchmark)
        val scenario = FakeExecutionScenario.Cycling(listOf("expected-1"), usage(), latency())
        val firstRunWorker = worker(FakeExecutionAdapter(scenario), batchSize = 1)
        firstRunWorker.runOnce()
        scenario.invocationCount() shouldBe 1

        // 「再起動」を模擬: 新しいワーカーインスタンス（別instanceId）で残りを処理する。
        val secondRunWorker = worker(FakeExecutionAdapter(scenario), batchSize = 20)
        secondRunWorker.runOnce()

        // 1件目が再実行されていれば合計3回になるはず。完了済み1件+新規1件=合計2回のみ。
        scenario.invocationCount() shouldBe 2
        benchmarkRepository.findById(benchmark.benchmarkId)!!.status shouldBe BenchmarkStatus.Completed
    }

    /**
     * 二重実行防止の核心（`docs/prompts/m2-4b-c.md`「2つのワーカーが同じ項目を二重実行しない」、
     * 実プロバイダでは二重課金に直結する）。[JdbcBenchmarkItemResultRepositoryIntegrationTest]が
     * Claim行の重複が無いことをDBレベルで検証するのに対し、本テストは実行回数（＝プロバイダ
     * 呼出回数、課金相当）そのものが二重にならないことを、2つの[BenchmarkWorker]インスタンスを
     * 実際に並行実行して検証する。
     */
    @Test
    fun `2つのワーカーインスタンスが並行してrunOnceしても項目は合計実行回数どおりにしか実行されない`() {
        val promptKey = createApprovedPrompt()
        val itemCount = 20
        val datasetId = createDatasetWithItems(promptKey, itemCount)
        val target = BenchmarkTarget(UUID.randomUUID(), SemVer(1, 0, 0))
        val nRepetitions = 2
        val benchmark =
            Benchmark.create(
                promptKey,
                datasetId,
                listOf(target),
                setOf(BenchmarkMetricType.Accuracy),
                nRepetitions,
            )
        benchmarkRepository.save(benchmark)
        // 2ワーカーが同一Scenarioインスタンスを共有する（invocationCount()がプロセス全体の
        // 実行回数を正しく合算するため、AtomicIntegerで実装したFakeExecutionScenario.Cycling
        // 自体のスレッドセーフ性の検証も兼ねる）。
        val scenario = FakeExecutionScenario.Cycling(listOf("expected"), usage(), latency())
        val sharedAdapter = FakeExecutionAdapter(scenario)
        val workerA = worker(sharedAdapter, batchSize = itemCount, instanceId = "worker-a")
        val workerB = worker(sharedAdapter, batchSize = itemCount, instanceId = "worker-b")

        val executor = Executors.newFixedThreadPool(2)
        val startLatch = CountDownLatch(1)
        val taskA =
            executor.submit {
                startLatch.await()
                workerA.runOnce()
            }
        val taskB =
            executor.submit {
                startLatch.await()
                workerB.runOnce()
            }
        startLatch.countDown()
        taskA.get(30, TimeUnit.SECONDS)
        taskB.get(30, TimeUnit.SECONDS)
        executor.shutdown()

        // どちらのワーカーもPendingピックアップを試みるため、Benchmark自体は既にRunningの
        // 可能性がある。念のためもう一度どちらかにrunOnce()を呼ばせ、materialize漏れが
        // 無いことを確定させてから検証する。
        workerA.runOnce()

        scenario.invocationCount() shouldBe itemCount * nRepetitions
        benchmarkRepository.findById(benchmark.benchmarkId)!!.status shouldBe BenchmarkStatus.Completed
    }

    private fun onlyItemResultRow(): Map<String, Any?> =
        jdbcTemplate.queryForMap(
            "SELECT * FROM benchmark_item_results",
            MapSqlParameterSource(),
        )

    private fun usage() = promptengine.domain.execution.Usage(TokenCount(1), TokenCount(1))

    private fun latency() = promptengine.domain.shared.LatencyMs(1)

    private companion object {
        val TEST_TOKENIZER = promptengine.domain.tokenizer.TokenizerPlugin { text -> TokenCount(text.length) }

        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")
    }
}
