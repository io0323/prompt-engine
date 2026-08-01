package promptengine.application.pipeline

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.composition.CompiledPrompt
import promptengine.domain.composition.CompositionMode
import promptengine.domain.composition.CompositionService
import promptengine.domain.context.ContextBindingSet
import promptengine.domain.context.ContextRequirement
import promptengine.domain.context.ContextResolverChain
import promptengine.domain.event.EventContext
import promptengine.domain.execution.ExecutionEngine
import promptengine.domain.execution.ExecutionOutcome
import promptengine.domain.execution.ExecutionPolicy
import promptengine.domain.optimization.ModelProfile
import promptengine.domain.optimization.OptimizationEngine
import promptengine.domain.optimization.OptimizationOutcome
import promptengine.domain.parsing.OutputSchema
import promptengine.domain.pipeline.PipelineContext
import promptengine.domain.pipeline.PipelineMode
import promptengine.domain.pipeline.PipelineRequest
import promptengine.domain.prompt.NewPromptVersion
import promptengine.domain.prompt.Prompt
import promptengine.domain.prompt.PromptAlias
import promptengine.domain.prompt.PromptAliasRepository
import promptengine.domain.prompt.PromptContent
import promptengine.domain.prompt.PromptDomainEvent
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.PromptRepository
import promptengine.domain.prompt.PromptVersionNotFoundException
import promptengine.domain.prompt.VersionRef
import promptengine.domain.render.RenderEngine
import promptengine.domain.render.RenderedPrompt
import promptengine.domain.shared.Cost
import promptengine.domain.shared.PromptRequest
import promptengine.domain.shared.SemVer
import promptengine.domain.shared.TokenCount
import promptengine.domain.template.ast.TextNode
import promptengine.domain.validation.ValidationEngine
import promptengine.domain.validation.ValidationReport
import promptengine.domain.variable.BindingSet
import promptengine.domain.variable.VariableDefinition
import promptengine.domain.variable.VariableResolverChain
import java.time.Instant

/**
 * 各Stageの前段ステージ未実行ガード（`checkNotNull`）が実際に発火することの単体テスト。
 *
 * これらのガードは、`PipelineFactory`（ADR-0015決定6）が保証する正しいステージ順序の下
 * では通常到達しないが、将来`prompt-engine-bootstrap`（P9）の配線を誤った場合に備えた
 * 防御コードである（本物のガード欠落ではなく、誤配線を早期検出するための意図的な
 * 防御的実装）。分岐カバレッジ監査でこれらを「到達不能」と扱わず、実際に
 * ステージを単独で誤った順序（前段未実行）で呼び出して検証する。
 *
 * `RenderingStage`/`OptimizationStage`はかつて`variableBindings`/`contextBindings`が
 * `null`の場合に`?: BindingSet.empty()`等の空既定へフォールバックしていたが、これは
 * 他の8ステージと異なり未束縛のまま「成功」してしまい、壊れた出力が正常系として
 * 下流へ流れる危険があったため撤去した（ADR-0015決定4修正）。両ステージも他の
 * ステージと同じ`checkNotNull`によるfail-fastへ統一したことをこのテストで固定する。
 */
class PipelineStageGuardsTest {
    private val modelProfile =
        ModelProfile(
            maxContextTokens = TokenCount(1_000),
            tokenizerId = "test-tokenizer",
            costPerToken = Cost(java.math.BigDecimal.ZERO),
        )

    private fun context(
        mode: PipelineMode = PipelineMode.FULL_EXECUTION,
        executionPolicy: ExecutionPolicy? = ExecutionPolicy(timeoutMs = 1_000),
    ): PipelineContext =
        PipelineContext(
            request =
                PipelineRequest(
                    promptKey = PromptKey("support/faq"),
                    versionRef = VersionRef.Latest,
                    variableResolution = PromptRequest(),
                    modelProfile = modelProfile,
                    budget = TokenCount(1_000),
                    executionPolicy = executionPolicy,
                ),
            mode = mode,
            traceId = "trace-guard-test",
        )

    private fun compiledPrompt(): CompiledPrompt =
        CompiledPrompt(
            body = listOf(TextNode("hi")),
            dependencies = emptyList(),
            variables = emptyList(),
            contextRequirements = emptyList(),
        )

    private object UnreachableCompositionService : CompositionService {
        override fun compile(
            promptKey: PromptKey,
            promptVersion: promptengine.domain.prompt.PromptVersion,
            mode: CompositionMode,
        ): CompiledPrompt = throw AssertionError("must not be called: MergeStage guard should fire first")
    }

    private object UnreachableVariableResolverChain : VariableResolverChain {
        override fun resolveAll(
            definitions: List<VariableDefinition>,
            request: PromptRequest,
        ): BindingSet = throw AssertionError("must not be called: ResolveVariablesStage guard should fire first")
    }

    private object UnreachableContextResolverChain : ContextResolverChain {
        override fun resolve(
            requirements: List<ContextRequirement>,
            request: PromptRequest,
        ): ContextBindingSet = throw AssertionError("must not be called: ResolveContextStage guard should fire first")
    }

    private object UnreachableValidationEngine : ValidationEngine {
        override fun validate(
            compiled: CompiledPrompt,
            variableBindings: BindingSet,
            contextBindings: ContextBindingSet,
        ): ValidationReport = throw AssertionError("must not be called: ValidationStage guard should fire first")
    }

    private object UnreachableOptimizationEngine : OptimizationEngine {
        override fun optimize(
            compiled: CompiledPrompt,
            variableBindings: BindingSet,
            contextBindings: ContextBindingSet,
            profile: ModelProfile,
            budget: TokenCount,
        ): OptimizationOutcome = throw AssertionError("must not be called: OptimizationStage guard should fire first")
    }

    private object UnreachableRenderEngine : RenderEngine {
        override fun render(
            compiled: CompiledPrompt,
            variableBindings: BindingSet,
            contextBindings: ContextBindingSet,
            outputFormat: promptengine.domain.render.OutputFormat,
            outputSchema: OutputSchema?,
        ): RenderedPrompt = throw AssertionError("must not be called: RenderingStage guard should fire first")
    }

    private object UnreachableExecutionEngine : ExecutionEngine {
        override fun run(
            rendered: RenderedPrompt,
            policy: ExecutionPolicy,
            schema: OutputSchema?,
            budget: TokenCount,
        ): ExecutionOutcome = throw AssertionError("must not be called: ExecutionStage guard should fire first")
    }

    @Test
    fun `MergeStage は Load未実行時 promptVersion欠如 でIllegalStateException`() {
        shouldThrow<IllegalStateException> { MergeStage(UnreachableCompositionService).execute(context()) }
    }

    @Test
    fun `ResolveVariablesStage は Merge未実行時 compiled欠如 でIllegalStateException`() {
        shouldThrow<IllegalStateException> {
            ResolveVariablesStage(
                UnreachableVariableResolverChain,
            ).execute(context())
        }
    }

    @Test
    fun `ResolveContextStage は Merge未実行時 compiled欠如 でIllegalStateException`() {
        shouldThrow<IllegalStateException> { ResolveContextStage(UnreachableContextResolverChain).execute(context()) }
    }

    @Test
    fun `ValidationStage は Merge未実行時 compiled欠如 でIllegalStateException`() {
        shouldThrow<IllegalStateException> { ValidationStage(UnreachableValidationEngine).execute(context()) }
    }

    @Test
    fun `OptimizationStage は Merge未実行時 compiled欠如 でIllegalStateException`() {
        shouldThrow<IllegalStateException> { OptimizationStage(UnreachableOptimizationEngine).execute(context()) }
    }

    @Test
    fun `OptimizationStage は ResolveVariables未実行時 variableBindings欠如 でIllegalStateException`() {
        val compiledOnly = context().copy(compiled = compiledPrompt())
        shouldThrow<IllegalStateException> { OptimizationStage(UnreachableOptimizationEngine).execute(compiledOnly) }
    }

    @Test
    fun `OptimizationStage は ResolveContext未実行時 contextBindings欠如 でIllegalStateException`() {
        val compiledAndVariables = context().copy(compiled = compiledPrompt(), variableBindings = BindingSet.empty())
        shouldThrow<IllegalStateException> {
            OptimizationStage(UnreachableOptimizationEngine).execute(compiledAndVariables)
        }
    }

    @Test
    fun `RenderingStage は Merge未実行時 compiled欠如 でIllegalStateException`() {
        shouldThrow<IllegalStateException> { RenderingStage(UnreachableRenderEngine).execute(context()) }
    }

    @Test
    fun `RenderingStage は ResolveVariables未実行時 variableBindings欠如 でIllegalStateException`() {
        val compiledOnly = context().copy(compiled = compiledPrompt())
        shouldThrow<IllegalStateException> { RenderingStage(UnreachableRenderEngine).execute(compiledOnly) }
    }

    @Test
    fun `RenderingStage は ResolveContext未実行時 contextBindings欠如 でIllegalStateException`() {
        val compiledAndVariables = context().copy(compiled = compiledPrompt(), variableBindings = BindingSet.empty())
        shouldThrow<IllegalStateException> { RenderingStage(UnreachableRenderEngine).execute(compiledAndVariables) }
    }

    @Test
    fun `RenderingStageのcheckNotNull由来のIllegalStateExceptionはStageErrorMapperでRENDER_ERRORにならずINTERNAL_ERRORになる`() {
        val exception =
            shouldThrow<IllegalStateException> { RenderingStage(UnreachableRenderEngine).execute(context()) }

        StageErrorMapper.errorCodeFor(exception) shouldBe StageErrorMapper.INTERNAL_ERROR
    }

    @Test
    fun `ExecutionStage は Rendering未実行時 rendered欠如 でIllegalStateException`() {
        shouldThrow<IllegalStateException> { ExecutionStage(UnreachableExecutionEngine).execute(context()) }
    }

    @Test
    fun `ExecutionStage は executionPolicy未指定時 RENDER_ONLYやCOMPILE_ONLYの誤用 でIllegalStateException`() {
        val renderedContext =
            context(executionPolicy = null).copy(
                rendered =
                    RenderedPrompt(
                        listOf(
                            promptengine.domain.render.RenderedMessage(
                                promptengine.domain.render.MessageRole.USER,
                                "hi",
                            ),
                        ),
                        promptengine.domain.render.OutputFormat.TEXT,
                        TokenCount(2),
                        "hash",
                    ),
            )

        shouldThrow<IllegalStateException> { ExecutionStage(UnreachableExecutionEngine).execute(renderedContext) }
    }

    @Test
    fun `ResponseParsingStage は Execution未実行時 executionOutcome欠如 でIllegalStateException`() {
        shouldThrow<IllegalStateException> { ResponseParsingStage().execute(context()) }
    }

    @Test
    fun `EvaluationStage は Execution未実行時 executionOutcome欠如 でIllegalStateException`() {
        val eventBusAdapter =
            object : promptengine.domain.event.EventBusAdapter {
                override fun publish(event: promptengine.domain.event.DomainEvent) =
                    throw AssertionError("must not be called: EvaluationStage guard should fire first")
            }

        shouldThrow<IllegalStateException> { EvaluationStage(eventBusAdapter).execute(context()) }
    }

    // ---- LoadStage: VersionRef 3種の分岐 ----

    private val eventContext = EventContext(actor = "user:test", traceId = "fixture-trace", occurredAt = Instant.EPOCH)

    private class FakePromptRepository : PromptRepository {
        private val prompts = mutableMapOf<PromptKey, Prompt>()

        fun put(prompt: Prompt) {
            prompts[prompt.key] = prompt
        }

        override fun findByKey(key: PromptKey): Prompt? = prompts[key]

        override fun save(
            prompt: Prompt,
            events: List<PromptDomainEvent>,
        ): Prompt {
            prompts[prompt.key] = prompt
            return prompt
        }
    }

    private class FakePromptAliasRepository : PromptAliasRepository {
        private val aliases = mutableMapOf<Pair<PromptKey, String>, PromptAlias>()

        fun put(alias: PromptAlias) {
            aliases[alias.promptKey to alias.alias] = alias
        }

        override fun find(
            promptKey: PromptKey,
            alias: String,
        ): PromptAlias? = aliases[promptKey to alias]

        override fun findAll(promptKey: PromptKey): List<PromptAlias> =
            aliases.values.filter { it.promptKey == promptKey }

        override fun upsert(alias: PromptAlias) {
            aliases[alias.promptKey to alias.alias] = alias
        }
    }

    private fun wrap(key: String): String = "---\npe: \"1\"\nkind: prompt\nkey: $key\n---\nhello"

    private fun draftPrompt(
        key: PromptKey,
        semVer: SemVer,
    ): Prompt = Prompt.create(key, NewPromptVersion(semVer, PromptContent(wrap(key.value))), eventContext).first

    private fun publishedPrompt(
        key: PromptKey,
        semVer: SemVer,
    ): Prompt {
        val created = draftPrompt(key, semVer)
        val inReview = created.submitForReview(semVer, validationPassed = true)
        val approved = inReview.approve(semVer, approvalCount = 1, requiredApprovalCount = 1)
        return approved.publish(semVer, allDependenciesPublished = true, eventContext).first
    }

    @Test
    fun `LoadStage VersionRef Fixed は該当SemVerを解決する`() {
        val key = PromptKey("support/fixed-found")
        val repo = FakePromptRepository()
        repo.put(publishedPrompt(key, SemVer(1, 0, 0)))
        val stage = LoadStage(repo, FakePromptAliasRepository())
        val request =
            PipelineRequest(key, VersionRef.Fixed(SemVer(1, 0, 0)), PromptRequest(), modelProfile, TokenCount(1_000))

        val result = stage.execute(PipelineContext(request, PipelineMode.COMPILE_ONLY, "trace"))

        result.promptVersion?.semVer shouldBe SemVer(1, 0, 0)
    }

    @Test
    fun `LoadStage VersionRef Fixed は該当SemVerが無ければPromptVersionNotFoundException`() {
        val key = PromptKey("support/fixed-missing")
        val repo = FakePromptRepository()
        repo.put(publishedPrompt(key, SemVer(1, 0, 0)))
        val stage = LoadStage(repo, FakePromptAliasRepository())
        val request =
            PipelineRequest(key, VersionRef.Fixed(SemVer(9, 9, 9)), PromptRequest(), modelProfile, TokenCount(1_000))

        shouldThrow<PromptVersionNotFoundException> {
            stage.execute(PipelineContext(request, PipelineMode.COMPILE_ONLY, "trace"))
        }
    }

    @Test
    fun `LoadStage VersionRef Latest はPublished版が無ければPromptVersionNotFoundException`() {
        val key = PromptKey("support/latest-missing")
        val repo = FakePromptRepository()
        repo.put(draftPrompt(key, SemVer(1, 0, 0)))
        val stage = LoadStage(repo, FakePromptAliasRepository())
        val request = PipelineRequest(key, VersionRef.Latest, PromptRequest(), modelProfile, TokenCount(1_000))

        shouldThrow<PromptVersionNotFoundException> {
            stage.execute(PipelineContext(request, PipelineMode.COMPILE_ONLY, "trace"))
        }
    }

    @Test
    fun `LoadStage VersionRef Alias は登録済みAliasの参照先Versionを解決する`() {
        val key = PromptKey("support/alias-found")
        val repo = FakePromptRepository()
        repo.put(publishedPrompt(key, SemVer(1, 0, 0)))
        val aliasRepo = FakePromptAliasRepository()
        aliasRepo.put(PromptAlias(key, "stable", SemVer(1, 0, 0)))
        val stage = LoadStage(repo, aliasRepo)
        val request = PipelineRequest(key, VersionRef.Alias("stable"), PromptRequest(), modelProfile, TokenCount(1_000))

        val result = stage.execute(PipelineContext(request, PipelineMode.COMPILE_ONLY, "trace"))

        result.promptVersion?.semVer shouldBe SemVer(1, 0, 0)
    }

    @Test
    fun `LoadStage VersionRef Alias は未登録ならPromptVersionNotFoundException`() {
        val key = PromptKey("support/alias-missing")
        val repo = FakePromptRepository()
        repo.put(publishedPrompt(key, SemVer(1, 0, 0)))
        val stage = LoadStage(repo, FakePromptAliasRepository())
        val request = PipelineRequest(key, VersionRef.Alias("stable"), PromptRequest(), modelProfile, TokenCount(1_000))

        shouldThrow<PromptVersionNotFoundException> {
            stage.execute(PipelineContext(request, PipelineMode.COMPILE_ONLY, "trace"))
        }
    }

    // ---- 全12ステージの一貫したfail-fast方針 ----

    /**
     * 前段の出力（`compiled`/`variableBindings`/`contextBindings`/`rendered`/`executionOutcome`）に
     * 依存する9ステージ種別（Merge・ResolveVariables・ResolveContext・Validation・
     * Optimization・Rendering・Execution・ResponseParsing・Evaluation）はすべて
     * `checkNotNull`で前段未実行を拒否し、空既定へフォールバックする例外を作らない
     * （ADR-0015決定4修正、RenderingStage/OptimizationStageの`?: BindingSet.empty()`除去）。
     *
     * Load（前段が無い最初のステージ）・Import（Mergeが処理済みのため読み取るフィールドが
     * 無い素通しステージ）・Audit（前段の結果に関わらず常に実行し記録する契約、ADR-0015決定7）
     * の3ステージはこの一貫性の対象外（前段依存の`checkNotNull`を持つ設計ではないため）。
     */
    @Test
    fun `前段の出力に依存する9ステージ種別は全て前段未実行に対し一貫してfail-fastする`() {
        val bare = context()
        val compiledOnly = bare.copy(compiled = compiledPrompt())
        val compiledAndVariables = compiledOnly.copy(variableBindings = BindingSet.empty())
        val throwingEventBusAdapter =
            object : promptengine.domain.event.EventBusAdapter {
                override fun publish(event: promptengine.domain.event.DomainEvent) =
                    throw AssertionError("must not be called: EvaluationStage guard should fire first")
            }

        val guardedInvocations: List<Pair<String, () -> Unit>> =
            listOf(
                "Merge" to { MergeStage(UnreachableCompositionService).execute(bare) },
                "ResolveVariables" to { ResolveVariablesStage(UnreachableVariableResolverChain).execute(bare) },
                "ResolveContext" to { ResolveContextStage(UnreachableContextResolverChain).execute(bare) },
                "Validation" to { ValidationStage(UnreachableValidationEngine).execute(bare) },
                "Optimization(compiled)" to { OptimizationStage(UnreachableOptimizationEngine).execute(bare) },
                "Optimization(variableBindings)" to {
                    OptimizationStage(UnreachableOptimizationEngine).execute(compiledOnly)
                },
                "Optimization(contextBindings)" to {
                    OptimizationStage(UnreachableOptimizationEngine).execute(compiledAndVariables)
                },
                "Rendering(compiled)" to { RenderingStage(UnreachableRenderEngine).execute(bare) },
                "Rendering(variableBindings)" to { RenderingStage(UnreachableRenderEngine).execute(compiledOnly) },
                "Rendering(contextBindings)" to {
                    RenderingStage(UnreachableRenderEngine).execute(compiledAndVariables)
                },
                "Execution" to { ExecutionStage(UnreachableExecutionEngine).execute(bare) },
                "ResponseParsing" to { ResponseParsingStage().execute(bare) },
                "Evaluation" to { EvaluationStage(throwingEventBusAdapter).execute(bare) },
            )

        guardedInvocations.forEach { (label, invoke) ->
            withClue(label) { shouldThrow<IllegalStateException> { invoke() } }
        }
    }
}
