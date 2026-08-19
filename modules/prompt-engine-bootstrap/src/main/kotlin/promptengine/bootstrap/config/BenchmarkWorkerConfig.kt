package promptengine.bootstrap.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.EnableScheduling
import promptengine.domain.benchmark.BenchmarkItemResultRepository
import promptengine.domain.benchmark.BenchmarkRepository
import promptengine.domain.benchmark.BenchmarkScoringRule
import promptengine.domain.benchmark.GoldenDatasetRepository
import promptengine.domain.optimization.ModelProfile
import promptengine.domain.pipeline.PipelineRunner
import promptengine.engine.benchmark.ConsistencyScoringRule
import promptengine.engine.benchmark.DeterminismScoringRule
import promptengine.engine.benchmark.NormalizedExactMatchScoringRule
import promptengine.infrastructure.benchmark.BenchmarkWorker
import java.net.InetAddress
import java.time.Duration
import java.util.UUID

/**
 * このプロセスのBenchmarkワーカーとしてのクレーム識別子（`claimed_by`、ADR-0035決定3）。
 * [OutboxRelayInstanceId][promptengine.bootstrap.config.OutboxRelayInstanceId]と同じ理由で
 * 専用の型として持つ（型ベース解決の曖昧さを避ける）。
 */
data class BenchmarkWorkerInstanceId(val value: String)

/**
 * Benchmark実行ワーカーのDI配線（ADR-0035決定3、フェーズ(c)）。[OutboxRelayConfig]と同じく
 * `production`プロファイル・`promptengine.scheduler.enabled`でのみ有効化する。
 */
@Configuration
@Profile("production")
@ConditionalOnProperty(
    prefix = "promptengine.scheduler",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
@EnableScheduling
@EnableConfigurationProperties(BenchmarkWorkerProperties::class)
class BenchmarkWorkerConfig {
    @Bean
    fun benchmarkWorkerInstanceId(): BenchmarkWorkerInstanceId =
        BenchmarkWorkerInstanceId("${hostName()}-${UUID.randomUUID()}")

    @Bean
    fun accuracyScoringRule(): BenchmarkScoringRule = NormalizedExactMatchScoringRule()

    @Bean
    fun consistencyScoringRule(): BenchmarkScoringRule = ConsistencyScoringRule()

    @Bean
    fun determinismScoringRule(): BenchmarkScoringRule = DeterminismScoringRule()

    @Suppress("LongParameterList")
    @Bean
    fun benchmarkWorker(
        benchmarkRepository: BenchmarkRepository,
        goldenDatasetRepository: GoldenDatasetRepository,
        benchmarkItemResultRepository: BenchmarkItemResultRepository,
        pipelineRunner: PipelineRunner,
        scoringRules: List<BenchmarkScoringRule>,
        modelProfile: ModelProfile,
        instanceId: BenchmarkWorkerInstanceId,
        properties: BenchmarkWorkerProperties,
    ): BenchmarkWorker =
        BenchmarkWorker(
            benchmarkRepository,
            goldenDatasetRepository,
            benchmarkItemResultRepository,
            pipelineRunner,
            scoringRules,
            modelProfile,
            instanceId.value,
            Duration.ofSeconds(properties.claimTimeoutSeconds),
            properties.batchSize,
            properties.executionTimeoutMs,
        )

    /**
     * [BenchmarkWorkerScheduler]は`@Component`で自己登録せず、ここで`@Bean`として構築する
     * （CLAUDE.md「具象クラスのDI結線はbootstrapのConfigurationクラスでのみ行う」、
     * [OutboxRelayConfig.outboxRelayScheduler]と同じ規約）。
     */
    @Bean
    fun benchmarkWorkerScheduler(benchmarkWorker: BenchmarkWorker): BenchmarkWorkerScheduler =
        BenchmarkWorkerScheduler(benchmarkWorker)

    private fun hostName(): String = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("unknown-host")
}
