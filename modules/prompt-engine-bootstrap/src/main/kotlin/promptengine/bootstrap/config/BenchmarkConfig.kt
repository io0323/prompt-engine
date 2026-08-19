package promptengine.bootstrap.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import promptengine.application.command.CancelBenchmarkHandler
import promptengine.application.command.CreateBenchmarkHandler
import promptengine.application.command.CreateGoldenDatasetHandler
import promptengine.application.query.GetBenchmarkHandler
import promptengine.application.query.GetBenchmarkResultsHandler
import promptengine.application.query.GetGoldenDatasetHandler
import promptengine.domain.benchmark.BenchmarkItemResultRepository
import promptengine.domain.benchmark.BenchmarkRepository
import promptengine.domain.benchmark.GoldenDatasetRepository
import promptengine.domain.prompt.PromptRepository
import promptengine.domain.shared.IdempotentCommandExecutor

/**
 * Benchmark/GoldenDatasetコンテキスト（`Benchmark`/`GoldenDataset`、設計書§4.1、ADR-0035）の
 * DI配線（[ExperimentConfig]と同様、設計書§13.1のエンドポイントと1:1対応する）。
 */
@Configuration
class BenchmarkConfig {
    @Bean
    fun createBenchmarkHandler(
        promptRepository: PromptRepository,
        goldenDatasetRepository: GoldenDatasetRepository,
        benchmarkRepository: BenchmarkRepository,
        idempotentCommandExecutor: IdempotentCommandExecutor,
    ): CreateBenchmarkHandler =
        CreateBenchmarkHandler(
            promptRepository,
            goldenDatasetRepository,
            benchmarkRepository,
            idempotentCommandExecutor,
        )

    @Bean
    fun getBenchmarkHandler(
        benchmarkRepository: BenchmarkRepository,
        goldenDatasetRepository: GoldenDatasetRepository,
        benchmarkItemResultRepository: BenchmarkItemResultRepository,
    ): GetBenchmarkHandler =
        GetBenchmarkHandler(benchmarkRepository, goldenDatasetRepository, benchmarkItemResultRepository)

    @Bean
    fun cancelBenchmarkHandler(
        benchmarkRepository: BenchmarkRepository,
        idempotentCommandExecutor: IdempotentCommandExecutor,
    ): CancelBenchmarkHandler = CancelBenchmarkHandler(benchmarkRepository, idempotentCommandExecutor)

    @Bean
    fun getBenchmarkResultsHandler(
        benchmarkRepository: BenchmarkRepository,
        benchmarkItemResultRepository: BenchmarkItemResultRepository,
    ): GetBenchmarkResultsHandler = GetBenchmarkResultsHandler(benchmarkRepository, benchmarkItemResultRepository)

    @Bean
    fun createGoldenDatasetHandler(
        promptRepository: PromptRepository,
        goldenDatasetRepository: GoldenDatasetRepository,
        idempotentCommandExecutor: IdempotentCommandExecutor,
    ): CreateGoldenDatasetHandler =
        CreateGoldenDatasetHandler(promptRepository, goldenDatasetRepository, idempotentCommandExecutor)

    @Bean
    fun getGoldenDatasetHandler(goldenDatasetRepository: GoldenDatasetRepository): GetGoldenDatasetHandler =
        GetGoldenDatasetHandler(goldenDatasetRepository)
}
