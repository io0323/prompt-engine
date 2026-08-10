package promptengine.bootstrap.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import promptengine.application.pipeline.AuditStage
import promptengine.application.pipeline.CompileUseCase
import promptengine.application.pipeline.EvaluationStage
import promptengine.application.pipeline.ExecuteUseCase
import promptengine.application.pipeline.ExecutionStage
import promptengine.application.pipeline.ImportStage
import promptengine.application.pipeline.LoadStage
import promptengine.application.pipeline.MergeStage
import promptengine.application.pipeline.OptimizationStage
import promptengine.application.pipeline.PipelineFactory
import promptengine.application.pipeline.PipelineOrchestrator
import promptengine.application.pipeline.RenderUseCase
import promptengine.application.pipeline.RenderingStage
import promptengine.application.pipeline.ResolveContextStage
import promptengine.application.pipeline.ResolveVariablesStage
import promptengine.application.pipeline.ResponseParsingStage
import promptengine.application.pipeline.ValidationStage
import promptengine.domain.audit.AuditFailureHandler
import promptengine.domain.audit.AuditRepository
import promptengine.domain.composition.CompositionService
import promptengine.domain.context.ContextResolverChain
import promptengine.domain.event.EventBusAdapter
import promptengine.domain.execution.ExecutionEngine
import promptengine.domain.observability.MetricsRecorder
import promptengine.domain.optimization.OptimizationEngine
import promptengine.domain.pipeline.PipelineStage
import promptengine.domain.pipeline.PipelineTracer
import promptengine.domain.prompt.PromptAliasRepository
import promptengine.domain.prompt.PromptRepository
import promptengine.domain.render.RenderEngine
import promptengine.domain.shared.IdempotentCommandExecutor
import promptengine.domain.validation.ValidationEngine
import promptengine.domain.variable.VariableResolverChain

/**
 * P8のPipeline（12ステージ、`PipelineFactory`/`PipelineOrchestrator`）と、それを呼ぶP9bの
 * compile/render/execute UseCaseのDI配線（P9c）。
 *
 * ステージの並び順は設計書§2.6の通り厳守する（`PipelineFactory`の`init`が件数12を検証するのみで
 * 順序は検証しないため、ここで取り違えると誤動作が起こる）。
 */
@Configuration
class PipelineConfig {
    /**
     * 設計書§2.6が定める12ステージを、そのコンストラクタ順=実行順で組み立てる唯一の箇所。
     * 各引数は特定の1〜2ステージのみが必要とする協力者であり、意味のあるグルーピング単位が
     * 存在しない（無理に束ねると「何のための集約か」が読み取れないパラメータオブジェクトに
     * なり、KDocが警告する「順序の取り違え」リスクをかえって高める）ため、
     * `LongParameterList`を明示的に許容する。
     */
    @Suppress("LongParameterList")
    @Bean
    fun pipelineStages(
        promptRepository: PromptRepository,
        promptAliasRepository: PromptAliasRepository,
        compositionService: CompositionService,
        variableResolverChain: VariableResolverChain,
        contextResolverChain: ContextResolverChain,
        validationEngine: ValidationEngine,
        optimizationEngine: OptimizationEngine,
        renderEngine: RenderEngine,
        executionEngine: ExecutionEngine,
        eventBusAdapter: EventBusAdapter,
        auditRepository: AuditRepository,
        auditFailureHandler: AuditFailureHandler,
        metricsRecorder: MetricsRecorder,
    ): List<PipelineStage> =
        listOf(
            LoadStage(promptRepository, promptAliasRepository),
            MergeStage(compositionService),
            ImportStage(),
            ResolveVariablesStage(variableResolverChain),
            ResolveContextStage(contextResolverChain),
            ValidationStage(validationEngine, metricsRecorder),
            OptimizationStage(optimizationEngine),
            RenderingStage(renderEngine),
            ExecutionStage(executionEngine),
            ResponseParsingStage(),
            EvaluationStage(eventBusAdapter),
            AuditStage(auditRepository, auditFailureHandler),
        )

    @Bean
    fun pipelineFactory(pipelineStages: List<PipelineStage>): PipelineFactory = PipelineFactory(pipelineStages)

    @Bean
    fun auditStage(
        auditRepository: AuditRepository,
        auditFailureHandler: AuditFailureHandler,
    ): AuditStage = AuditStage(auditRepository, auditFailureHandler)

    @Bean
    fun pipelineOrchestrator(
        pipelineFactory: PipelineFactory,
        auditStage: AuditStage,
        pipelineTracer: PipelineTracer,
        metricsRecorder: MetricsRecorder,
    ): PipelineOrchestrator = PipelineOrchestrator(pipelineFactory, auditStage, pipelineTracer, metricsRecorder)

    @Bean
    fun compileUseCase(
        pipelineOrchestrator: PipelineOrchestrator,
        idempotentCommandExecutor: IdempotentCommandExecutor,
    ): CompileUseCase = CompileUseCase(pipelineOrchestrator, idempotentCommandExecutor)

    @Bean
    fun renderUseCase(
        pipelineOrchestrator: PipelineOrchestrator,
        idempotentCommandExecutor: IdempotentCommandExecutor,
    ): RenderUseCase = RenderUseCase(pipelineOrchestrator, idempotentCommandExecutor)

    @Bean
    fun executeUseCase(
        pipelineOrchestrator: PipelineOrchestrator,
        idempotentCommandExecutor: IdempotentCommandExecutor,
    ): ExecuteUseCase = ExecuteUseCase(pipelineOrchestrator, idempotentCommandExecutor)
}
