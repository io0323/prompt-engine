package promptengine.application.view

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.application.command.GoldenDatasetItemInput
import promptengine.domain.benchmark.BenchmarkMetricType
import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.SemVer
import java.util.UUID

class BenchmarkCommandFactoryTest {
    private val meta = RequestMeta(actor = "user:test", traceId = "trace-1", idempotencyKey = "idem-1")

    @Test
    fun `createBenchmarkCommand はpromptKey-datasetId-targets-metricsを正しくCommandへ変換する`() {
        val datasetId = UUID.randomUUID()

        val command =
            BenchmarkCommandFactory.createBenchmarkCommand(
                promptKey = "support/faq-answer",
                datasetId = datasetId.toString(),
                targetSemVers = listOf("1.0.0", "1.1.0"),
                metrics = setOf("Accuracy", "Consistency"),
                nRepetitions = 5,
                temperature = 0.7,
                meta = meta,
            )

        command.promptKey shouldBe PromptKey("support/faq-answer")
        command.datasetId shouldBe datasetId
        command.targets.map { it.semVer } shouldBe listOf(SemVer(1, 0, 0), SemVer(1, 1, 0))
        command.metrics shouldBe setOf(BenchmarkMetricType.Accuracy, BenchmarkMetricType.Consistency)
        command.nRepetitions shouldBe 5
        command.temperature shouldBe 0.7
        command.actor shouldBe "user:test"
        command.traceId shouldBe "trace-1"
        command.idempotencyKey shouldBe "idem-1"
    }

    @Test
    fun `createBenchmarkCommand はdatasetIdが不正な形式ならIllegalArgumentException`() {
        shouldThrow<IllegalArgumentException> {
            BenchmarkCommandFactory.createBenchmarkCommand(
                promptKey = "support/faq-answer",
                datasetId = "not-a-uuid",
                targetSemVers = listOf("1.0.0"),
                metrics = setOf("Accuracy"),
                nRepetitions = 3,
                temperature = null,
                meta = meta,
            )
        }
    }

    @Test
    fun `createBenchmarkCommand はtargetSemVersが不正な形式ならIllegalArgumentException`() {
        shouldThrow<IllegalArgumentException> {
            BenchmarkCommandFactory.createBenchmarkCommand(
                promptKey = "support/faq-answer",
                datasetId = UUID.randomUUID().toString(),
                targetSemVers = listOf("not-a-semver"),
                metrics = setOf("Accuracy"),
                nRepetitions = 3,
                temperature = null,
                meta = meta,
            )
        }
    }

    @Test
    fun `createBenchmarkCommand はmetricsが未知の文字列ならIllegalArgumentException`() {
        shouldThrow<IllegalArgumentException> {
            BenchmarkCommandFactory.createBenchmarkCommand(
                promptKey = "support/faq-answer",
                datasetId = UUID.randomUUID().toString(),
                targetSemVers = listOf("1.0.0"),
                metrics = setOf("Unknown"),
                nRepetitions = 3,
                temperature = null,
                meta = meta,
            )
        }
    }

    @Test
    fun `getBenchmarkQuery はbenchmarkIdをそのままQueryへ渡す`() {
        val benchmarkId = UUID.randomUUID()
        BenchmarkCommandFactory.getBenchmarkQuery(benchmarkId).benchmarkId shouldBe benchmarkId
    }

    @Test
    fun `getBenchmarkResultsQuery はbenchmarkIdをそのままQueryへ渡す`() {
        val benchmarkId = UUID.randomUUID()
        BenchmarkCommandFactory.getBenchmarkResultsQuery(benchmarkId).benchmarkId shouldBe benchmarkId
    }

    @Test
    fun `cancelBenchmarkCommand はbenchmarkId-metaを正しくCommandへ変換する`() {
        val benchmarkId = UUID.randomUUID()

        val command = BenchmarkCommandFactory.cancelBenchmarkCommand(benchmarkId, meta)

        command.benchmarkId shouldBe benchmarkId
        command.actor shouldBe "user:test"
        command.traceId shouldBe "trace-1"
        command.idempotencyKey shouldBe "idem-1"
    }

    @Test
    fun `createGoldenDatasetCommand はpromptKey-name-description-items-metaを正しくCommandへ変換する`() {
        val items = listOf(GoldenDatasetItemInput(mapOf("x" to "y"), emptyMap(), "expected", emptyMap()))

        val command =
            BenchmarkCommandFactory.createGoldenDatasetCommand(
                promptKey = "support/faq-answer",
                name = "smoke-test",
                description = "desc",
                items = items,
                meta = meta,
            )

        command.promptKey shouldBe PromptKey("support/faq-answer")
        command.name shouldBe "smoke-test"
        command.description shouldBe "desc"
        command.items shouldBe items
        command.actor shouldBe "user:test"
        command.traceId shouldBe "trace-1"
        command.idempotencyKey shouldBe "idem-1"
    }

    @Test
    fun `getGoldenDatasetQuery はdatasetIdをそのままQueryへ渡す`() {
        val datasetId = UUID.randomUUID()
        BenchmarkCommandFactory.getGoldenDatasetQuery(datasetId).datasetId shouldBe datasetId
    }
}
