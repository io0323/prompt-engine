package promptengine.application.pipeline

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import promptengine.domain.audit.AuditFailureHandler
import promptengine.domain.audit.AuditLogEntry
import promptengine.domain.audit.AuditOutcome
import promptengine.domain.audit.AuditQuery
import promptengine.domain.audit.AuditRecord
import promptengine.domain.audit.AuditRepository
import promptengine.domain.composition.CircularDependencyException
import promptengine.domain.composition.FragmentReferenceNotFoundException
import promptengine.domain.composition.TemplateReferenceNotFoundException
import promptengine.domain.context.ContextRequirement
import promptengine.domain.context.ContextUnavailableException
import promptengine.domain.event.DomainEvent
import promptengine.domain.event.EventBusAdapter
import promptengine.domain.event.EventContext
import promptengine.domain.execution.ExecutionAdapter
import promptengine.domain.execution.ExecutionErrorType
import promptengine.domain.execution.ExecutionFailedException
import promptengine.domain.execution.ExecutionPolicy
import promptengine.domain.execution.RawResponse
import promptengine.domain.execution.Usage
import promptengine.domain.fragment.Fragment
import promptengine.domain.fragment.FragmentKey
import promptengine.domain.fragment.FragmentRepository
import promptengine.domain.optimization.ModelProfile
import promptengine.domain.optimization.TokenBudgetExceededException
import promptengine.domain.parsing.OutputFormatter
import promptengine.domain.parsing.OutputSchema
import promptengine.domain.parsing.ParseFailedException
import promptengine.domain.parsing.ParsedOutput
import promptengine.domain.pipeline.InvalidPipelineRequestException
import promptengine.domain.pipeline.PipelineMode
import promptengine.domain.pipeline.PipelineRequest
import promptengine.domain.pipeline.PipelineTracer
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
import promptengine.domain.render.OutputFormat
import promptengine.domain.render.RenderFailedException
import promptengine.domain.render.RenderedPrompt
import promptengine.domain.shared.Cost
import promptengine.domain.shared.ExtendsRefApi
import promptengine.domain.shared.LatencyMs
import promptengine.domain.shared.Page
import promptengine.domain.shared.PromptRequest
import promptengine.domain.shared.SemVer
import promptengine.domain.shared.SensitiveValue
import promptengine.domain.shared.TokenCount
import promptengine.domain.template.ExtendsRef
import promptengine.domain.template.NewTemplateVersion
import promptengine.domain.template.Template
import promptengine.domain.template.TemplateContent
import promptengine.domain.template.TemplateKey
import promptengine.domain.template.TemplateRepository
import promptengine.domain.tokenizer.TokenizerPlugin
import promptengine.domain.validation.Finding
import promptengine.domain.validation.Severity
import promptengine.domain.validation.ValidationRule
import promptengine.domain.variable.VariableDefinition
import promptengine.domain.variable.VariableSource
import promptengine.domain.variable.VariableType
import promptengine.domain.variable.VariableUnresolvedException
import promptengine.engine.compiler.CompositionServiceImpl
import promptengine.engine.execution.ExecutionCoordinator
import promptengine.engine.formatter.TextOutputFormatter
import promptengine.engine.optimization.OptimizationEngineImpl
import promptengine.engine.render.DefaultTemplateEngine
import promptengine.engine.render.RenderEngineImpl
import promptengine.engine.resolver.ContextResolverImpl
import promptengine.engine.resolver.VariableResolverChainImpl
import promptengine.engine.validation.ValidationEngineImpl
import java.math.BigDecimal
import java.time.Instant

/**
 * [PipelineOrchestrator]の結合テスト（設計書§2.6・§10、ADR-0015）。
 *
 * 実際の12ステージ実装（薄い委譲層）と、P3c〜P7で確立済みの実Engine
 * （[CompositionServiceImpl]・[VariableResolverChainImpl]・[ContextResolverImpl]・
 * [ValidationEngineImpl]・[OptimizationEngineImpl]・[RenderEngineImpl]・
 * [ExecutionCoordinator]）を実際に結線し、サンプルの`.prompt`相当のDSLソースを
 * 3モード全てでend-to-end実行できることを検証する。
 *
 * ステージ⇔エラーコード対応（ADR-0015決定4の表）は、各異常系テストが該当ステージだけを
 * 失敗させる最小限のFake/Repository構成で実際に[PipelineOrchestrator.run]を実行し、
 * 投げられた例外の型と、[AuditRecord.outcome]に記録された`errorCode`の両方を検証する
 * ことで「この例外はこのステージ由来である」ことを直接証明する（[StageErrorMapper]自体の
 * 型→コード写像は[StageErrorMapperTest]が別途純粋単体で検証する）。
 */
@OptIn(ExtendsRefApi::class)
class PipelineOrchestratorTest {
    private val eventContext = EventContext(actor = "user:test", traceId = "fixture-trace", occurredAt = Instant.EPOCH)
    private val tokenizer = TokenizerPlugin { text -> TokenCount(text.length) }
    private val modelProfile =
        ModelProfile(
            maxContextTokens = TokenCount(1_000_000),
            tokenizerId = "test-tokenizer",
            costPerToken = Cost(BigDecimal.ZERO),
        )
    private val generousBudget = TokenCount(100_000)
    private val productNameVariable =
        VariableDefinition(
            name = "productName",
            type = VariableType.STRING,
            source = VariableSource.RUNTIME,
            required = true,
        )
    private val defaultKey = PromptKey("support/faq-e2e")
    private val defaultBody =
        """
        {{#block system}}
        あなたはサポート担当です。
        {{/block}}

        {{#block user}}
        製品「{{ productName }}」について回答してください。
        {{/block}}
        """.trimIndent()

    // ---- E2Eの3モード ----

    @Test
    fun `RENDER_ONLYモードは1〜8ステージのみ実行しrenderedまでを返す`() {
        val fixture = Fixture()
        val orchestrator = fixture.orchestrator()

        val result =
            orchestrator.run(
                request = fixture.baseRequest(),
                mode = PipelineMode.RENDER_ONLY,
                traceId = "trace-render-only",
            )

        result.rendered shouldNotBe null
        result.rendered!!.messages.isNotEmpty() shouldBe true
        result.executionOutcome shouldBe null
        result.parsedOutput shouldBe null
        result.stageDurationsMs.keys shouldContain "Rendering"
        result.stageDurationsMs.keys shouldBe
            setOf(
                "Load", "Merge", "Import", "ResolveVariables", "ResolveContext",
                "Validation", "Optimization", "Rendering",
            )
    }

    @Test
    fun `traceIdを省略した場合は自動生成されたIDが使われる`() {
        val fixture = Fixture()
        val orchestrator = fixture.orchestrator()

        val result = orchestrator.run(request = fixture.baseRequest(), mode = PipelineMode.RENDER_ONLY)

        result.traceId.isNotBlank() shouldBe true
    }

    @Test
    fun `FULL_EXECUTIONモードは1〜12ステージ全て実行しparsedOutputまでを返す`() {
        val fixture = Fixture()
        val orchestrator = fixture.orchestrator()

        val result =
            orchestrator.run(
                request = fixture.baseRequest(executionPolicy = ExecutionPolicy(timeoutMs = 5_000)),
                mode = PipelineMode.FULL_EXECUTION,
                traceId = "trace-full-execution",
            )

        result.executionOutcome shouldNotBe null
        result.parsedOutput shouldNotBe null
        result.stageDurationsMs.keys shouldBe
            setOf(
                "Load", "Merge", "Import", "ResolveVariables", "ResolveContext", "Validation",
                "Optimization", "Rendering", "Execution", "ResponseParsing", "Evaluation", "Audit",
            )
        fixture.recordingAuditRepository.records.single().outcome shouldBe AuditOutcome.Success
        fixture.recordingEventBusAdapter.published.single().eventType shouldBe "PromptExecuted"
    }

    @Test
    fun `COMPILE_ONLYモードは1〜3と6ステージのみ実行しvalidationReportまでを返す`() {
        val fixture = Fixture()
        val orchestrator = fixture.orchestrator()

        val result =
            orchestrator.run(
                request = fixture.baseRequest(),
                mode = PipelineMode.COMPILE_ONLY,
                traceId = "trace-compile-only",
            )

        result.compiled shouldNotBe null
        result.validationReport shouldNotBe null
        result.variableBindings shouldBe null
        result.rendered shouldBe null
        result.stageDurationsMs.keys shouldBe setOf("Load", "Merge", "Import", "Validation")
    }

    // ---- traceId伝播 ----

    @Test
    fun `traceIdは全ステージ Span PromptExecutedEvent AuditRecordへ同一値が伝播する`() {
        val fixture = Fixture()
        val orchestrator = fixture.orchestrator()
        val traceId = "trace-propagation-check"

        orchestrator.run(
            request = fixture.baseRequest(executionPolicy = ExecutionPolicy(timeoutMs = 5_000)),
            mode = PipelineMode.FULL_EXECUTION,
            traceId = traceId,
        )

        fixture.tracer.spans.map { it.traceId }.toSet() shouldBe setOf(traceId)
        fixture.tracer.spans.map { it.stageName } shouldBe
            listOf(
                "Load", "Merge", "Import", "ResolveVariables", "ResolveContext", "Validation",
                "Optimization", "Rendering", "Execution", "ResponseParsing", "Evaluation", "Audit",
            )
        fixture.recordingEventBusAdapter.published.single().traceId shouldBe traceId
        fixture.recordingAuditRepository.records.single().traceId shouldBe traceId
    }

    // ---- Auditステージ失敗時の非フェイル ----

    @Test
    fun `Audit追記が失敗しても本流のPipeline実行は失敗しない`() {
        val fixture = Fixture(auditRepository = ThrowingAuditRepository())
        val orchestrator = fixture.orchestrator()

        val result =
            orchestrator.run(
                request = fixture.baseRequest(executionPolicy = ExecutionPolicy(timeoutMs = 5_000)),
                mode = PipelineMode.FULL_EXECUTION,
                traceId = "trace-audit-failure",
            )

        result.parsedOutput shouldNotBe null
        fixture.auditFailureHandler.handledCauses.size shouldBe 1
    }

    // ---- Evaluationステージの非ブロッキング ----

    @Test
    fun `イベント発行が失敗してもPipeline全体は失敗せずAuditまで到達する`() {
        val fixture = Fixture(eventBusAdapter = ThrowingEventBusAdapter())
        val orchestrator = fixture.orchestrator()

        val result =
            orchestrator.run(
                request = fixture.baseRequest(executionPolicy = ExecutionPolicy(timeoutMs = 5_000)),
                mode = PipelineMode.FULL_EXECUTION,
                traceId = "trace-evaluation-failure",
            )

        result.stageDurationsMs.keys shouldContain "Audit"
        fixture.recordingAuditRepository.records.single().outcome shouldBe AuditOutcome.Success
    }

    // ---- ステージ⇔エラーコード対応（§13.3、ADR-0015決定4） ----

    /** [Fixture.recordingAuditRepository]に記録された唯一の[AuditRecord]がFailure([errorCode])であることを検証する。 */
    private fun Fixture.assertAuditedFailure(errorCode: String) {
        recordingAuditRepository.records.single().outcome shouldBe AuditOutcome.Failure(errorCode)
    }

    @Test
    fun `Load PromptVersionNotFoundException PROMPT_NOT_FOUND`() {
        val fixture = Fixture()
        val orchestrator = fixture.orchestrator()
        val request =
            fixture.baseRequest(
                promptKey = PromptKey("support/does-not-exist"),
                executionPolicy = ExecutionPolicy(timeoutMs = 5_000),
            )

        shouldThrow<PromptVersionNotFoundException> {
            orchestrator.run(request, PipelineMode.FULL_EXECUTION, "trace-load-error")
        }

        fixture.assertAuditedFailure(StageErrorMapper.PROMPT_NOT_FOUND)
        // 失敗したLoad自身のdurationがAuditRecordへ記録されていることを検証する
        // （CodeRabbitレビュー指摘: 従来は例外発生時にdurationの積み増しが行われず欠落していた）。
        fixture.recordingAuditRepository.records.single().stageDurationsMs.keys shouldContain "Load"
    }

    @Test
    fun `FULL_EXECUTIONでexecutionPolicy未指定はどのStageも実行せずINVALID_REQUESTとして記録される`() {
        val fixture = Fixture()
        val orchestrator = fixture.orchestrator()
        val request = fixture.baseRequest(executionPolicy = null)

        shouldThrow<InvalidPipelineRequestException> {
            orchestrator.run(request, PipelineMode.FULL_EXECUTION, "trace-missing-execution-policy")
        }

        fixture.assertAuditedFailure(StageErrorMapper.INVALID_REQUEST)
        // どのStageも実行されていない（入口の検証で即座に打ち切られる）ことを確認する。
        fixture.recordingAuditRepository.records.single().stageDurationsMs shouldBe emptyMap()
    }

    @Test
    fun `RENDER_ONLYモードでの失敗はAuditに記録されない FULL_EXECUTION以外は対象外`() {
        val fixture = Fixture()
        val orchestrator = fixture.orchestrator()
        val request = fixture.baseRequest(promptKey = PromptKey("support/does-not-exist"))

        shouldThrow<PromptVersionNotFoundException> {
            orchestrator.run(request, PipelineMode.RENDER_ONLY, "trace-render-only-error")
        }

        fixture.recordingAuditRepository.records shouldBe emptyList()
    }

    @Test
    fun `Merge TemplateReferenceNotFoundException TEMPLATE_NOT_FOUND`() {
        val fixture = Fixture()
        val key = PromptKey("support/extends-missing-template")
        fixture.promptRepository.put(
            fixture.publishedPrompt(key, extends = ExtendsRef(TemplateKey("templates/does-not-exist"))),
        )
        val orchestrator = fixture.orchestrator()

        shouldThrow<TemplateReferenceNotFoundException> {
            orchestrator.run(
                fixture.baseRequest(promptKey = key, executionPolicy = ExecutionPolicy(timeoutMs = 5_000)),
                PipelineMode.FULL_EXECUTION,
                "trace-merge-template",
            )
        }

        fixture.assertAuditedFailure(StageErrorMapper.TEMPLATE_NOT_FOUND)
    }

    @Test
    fun `Merge CircularDependencyException CIRCULAR_DEPENDENCY`() {
        val fixture = Fixture()
        val keyA = TemplateKey("templates/circular-a")
        val keyB = TemplateKey("templates/circular-b")
        fixture.templateRepository.addPublished(keyA, SemVer(1, 0, 0), "a", extends = ExtendsRef(keyB))
        fixture.templateRepository.addPublished(keyB, SemVer(1, 0, 0), "b", extends = ExtendsRef(keyA))
        val key = PromptKey("support/circular-extends")
        fixture.promptRepository.put(fixture.publishedPrompt(key, extends = ExtendsRef(keyA)))
        val orchestrator = fixture.orchestrator()

        shouldThrow<CircularDependencyException> {
            orchestrator.run(
                fixture.baseRequest(promptKey = key, executionPolicy = ExecutionPolicy(timeoutMs = 5_000)),
                PipelineMode.FULL_EXECUTION,
                "trace-merge-circular",
            )
        }

        fixture.assertAuditedFailure(StageErrorMapper.CIRCULAR_DEPENDENCY)
    }

    @Test
    fun `Merge Import経由 FragmentReferenceNotFoundException FRAGMENT_NOT_FOUND`() {
        val fixture = Fixture()
        val key = PromptKey("support/missing-fragment")
        fixture.promptRepository.put(
            fixture.publishedPrompt(
                key,
                bodyText =
                    """
                    {{#block system}}
                    {{> fragments/does-not-exist }}
                    {{/block}}

                    {{#block user}}
                    製品「{{ productName }}」について回答してください。
                    {{/block}}
                    """.trimIndent(),
            ),
        )
        val orchestrator = fixture.orchestrator()

        shouldThrow<FragmentReferenceNotFoundException> {
            orchestrator.run(
                fixture.baseRequest(promptKey = key, executionPolicy = ExecutionPolicy(timeoutMs = 5_000)),
                PipelineMode.FULL_EXECUTION,
                "trace-merge-fragment",
            )
        }

        fixture.assertAuditedFailure(StageErrorMapper.FRAGMENT_NOT_FOUND)
    }

    @Test
    fun `ResolveVariables VariableUnresolvedException VARIABLE_UNRESOLVED`() {
        val fixture = Fixture()
        val orchestrator = fixture.orchestrator()
        val request =
            fixture.baseRequest(
                variableResolution = PromptRequest(),
                executionPolicy = ExecutionPolicy(timeoutMs = 5_000),
            )

        shouldThrow<VariableUnresolvedException> {
            orchestrator.run(request, PipelineMode.FULL_EXECUTION, "trace-resolve-variables")
        }

        fixture.assertAuditedFailure(StageErrorMapper.VARIABLE_UNRESOLVED)
    }

    @Test
    fun `ResolveContext ContextUnavailableException CONTEXT_UNAVAILABLE`() {
        val fixture = Fixture()
        val key = PromptKey("support/requires-context")
        fixture.promptRepository.put(
            fixture.publishedPrompt(
                key,
                contextRequirements = listOf(ContextRequirement(scope = "system", required = listOf("now"))),
            ),
        )
        val orchestrator = fixture.orchestrator()

        shouldThrow<ContextUnavailableException> {
            orchestrator.run(
                fixture.baseRequest(promptKey = key, executionPolicy = ExecutionPolicy(timeoutMs = 5_000)),
                PipelineMode.FULL_EXECUTION,
                "trace-resolve-context",
            )
        }

        fixture.assertAuditedFailure(StageErrorMapper.CONTEXT_UNAVAILABLE)
    }

    @Test
    fun `Validation ValidationFailedException VALIDATION_FAILED`() {
        val fixture = Fixture(validationRules = listOf(AlwaysErrorValidationRule))
        val orchestrator = fixture.orchestrator()

        shouldThrow<promptengine.domain.validation.ValidationFailedException> {
            orchestrator.run(
                fixture.baseRequest(executionPolicy = ExecutionPolicy(timeoutMs = 5_000)),
                PipelineMode.FULL_EXECUTION,
                "trace-validation",
            )
        }

        fixture.assertAuditedFailure(StageErrorMapper.VALIDATION_FAILED)
    }

    @Test
    fun `Optimization TokenBudgetExceededException TOKEN_BUDGET_EXCEEDED`() {
        val fixture = Fixture()
        val orchestrator = fixture.orchestrator()
        val request = fixture.baseRequest(budget = TokenCount(0), executionPolicy = ExecutionPolicy(timeoutMs = 5_000))

        shouldThrow<TokenBudgetExceededException> {
            orchestrator.run(request, PipelineMode.FULL_EXECUTION, "trace-optimization")
        }

        fixture.assertAuditedFailure(StageErrorMapper.TOKEN_BUDGET_EXCEEDED)
    }

    @Test
    fun `Rendering 未登録OutputFormatter RenderFailedException RENDER_ERROR`() {
        val fixture = Fixture(outputFormatters = emptyMap())
        val orchestrator = fixture.orchestrator()

        shouldThrow<RenderFailedException> {
            orchestrator.run(
                fixture.baseRequest(executionPolicy = ExecutionPolicy(timeoutMs = 5_000)),
                PipelineMode.FULL_EXECUTION,
                "trace-rendering",
            )
        }

        fixture.assertAuditedFailure(StageErrorMapper.RENDER_ERROR)
    }

    @Test
    fun `Execution ExecutionFailedException EXECUTION_FAILED`() {
        val fixture = Fixture(executionAdapter = FailingExecutionAdapter())
        val orchestrator = fixture.orchestrator()
        val request = fixture.baseRequest(executionPolicy = ExecutionPolicy(timeoutMs = 5_000))

        shouldThrow<ExecutionFailedException> {
            orchestrator.run(request, PipelineMode.FULL_EXECUTION, "trace-execution")
        }

        fixture.assertAuditedFailure(StageErrorMapper.EXECUTION_FAILED)
    }

    @Test
    fun `Execution 経由のResponse Parsing ParseFailedException PARSE_FAILED`() {
        val fixture = Fixture(outputFormatters = mapOf(OutputFormat.TEXT to AlwaysFailingParseFormatter))
        val orchestrator = fixture.orchestrator()
        val request = fixture.baseRequest(executionPolicy = ExecutionPolicy(timeoutMs = 5_000))

        shouldThrow<ParseFailedException> {
            orchestrator.run(request, PipelineMode.FULL_EXECUTION, "trace-parse")
        }

        fixture.assertAuditedFailure(StageErrorMapper.PARSE_FAILED)
    }

    // ---- テスト用Fake/フィクスチャ ----

    /** 常にERROR severityのFindingを1件返すテスト用[ValidationRule]。 */
    private object AlwaysErrorValidationRule : ValidationRule {
        override fun id(): String = "always-error"

        override fun severity(): Severity = Severity.ERROR

        override fun validate(
            compiled: promptengine.domain.composition.CompiledPrompt,
            variableBindings: promptengine.domain.variable.BindingSet,
            contextBindings: promptengine.domain.context.ContextBindingSet,
        ): List<Finding> = listOf(Finding("always-error", "$.body", Severity.ERROR, "forced failure for test"))
    }

    /** 常に[ParseFailedException]を投げるテスト用[OutputFormatter]。 */
    private object AlwaysFailingParseFormatter : OutputFormatter {
        override fun format(): OutputFormat = OutputFormat.TEXT

        override fun instruction(schema: OutputSchema?): String = ""

        override fun parse(
            raw: String,
            schema: OutputSchema?,
        ): ParsedOutput = throw ParseFailedException(OutputFormat.TEXT, "forced failure for test")
    }

    private class FailingExecutionAdapter(
        private val errorType: ExecutionErrorType = ExecutionErrorType.SERVER_ERROR,
    ) : ExecutionAdapter {
        override fun execute(
            prompt: RenderedPrompt,
            policy: ExecutionPolicy,
        ): RawResponse = throw ExecutionFailedException(errorType, retryCount = 0)
    }

    private class SuccessExecutionAdapter : ExecutionAdapter {
        override fun execute(
            prompt: RenderedPrompt,
            policy: ExecutionPolicy,
        ): RawResponse = RawResponse(SensitiveValue.of("OK"), Usage(TokenCount(3), TokenCount(3)), LatencyMs(1))
    }

    private class RecordingPipelineTracer : PipelineTracer {
        data class SpanCall(val stageName: String, val traceId: String)

        val spans = mutableListOf<SpanCall>()

        override fun <T> withSpan(
            stageName: String,
            traceId: String,
            block: () -> T,
        ): T {
            spans += SpanCall(stageName, traceId)
            return block()
        }
    }

    private class RecordingEventBusAdapter : EventBusAdapter {
        val published = mutableListOf<DomainEvent>()

        override fun publish(event: DomainEvent) {
            published += event
        }
    }

    private class ThrowingEventBusAdapter : EventBusAdapter {
        override fun publish(event: DomainEvent): Nothing = error("event bus unavailable (test)")
    }

    private class RecordingAuditRepository : AuditRepository {
        val records = mutableListOf<AuditRecord>()

        override fun append(record: AuditRecord) {
            records += record
        }

        override fun record(entry: AuditLogEntry) = Unit

        override fun search(query: AuditQuery): Page<AuditLogEntry> = Page(emptyList(), query.page, query.size, 0)
    }

    private class ThrowingAuditRepository : AuditRepository {
        override fun append(record: AuditRecord): Nothing = error("audit store unavailable (test)")

        override fun record(entry: AuditLogEntry): Nothing = error("audit store unavailable (test)")

        override fun search(query: AuditQuery): Nothing = error("audit store unavailable (test)")
    }

    private class RecordingAuditFailureHandler : AuditFailureHandler {
        val handledCauses = mutableListOf<Throwable>()

        override fun handle(
            record: AuditRecord,
            cause: Throwable,
        ) {
            handledCauses += cause
        }
    }

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
        override fun find(
            promptKey: PromptKey,
            alias: String,
        ): PromptAlias? = null

        override fun findAll(promptKey: PromptKey): List<PromptAlias> = emptyList()

        override fun upsert(alias: PromptAlias) = Unit
    }

    private class FakeTemplateRepository : TemplateRepository {
        private val templates = mutableMapOf<TemplateKey, Template>()

        fun addPublished(
            key: TemplateKey,
            semVer: SemVer,
            bodyText: String,
            extends: ExtendsRef? = null,
        ) {
            val newVersion =
                NewTemplateVersion(semVer, TemplateContent(wrap(key.value, "template", bodyText)), extends = extends)
            var template = templates[key]?.newVersion(newVersion) ?: Template.create(key, newVersion)
            template = template.publish(semVer)
            templates[key] = template
        }

        override fun findByKey(key: TemplateKey): Template? = templates[key]

        override fun save(template: Template): Template {
            templates[template.key] = template
            return template
        }
    }

    private class FakeFragmentRepository : FragmentRepository {
        private val fragments = mutableMapOf<FragmentKey, Fragment>()

        override fun findByKey(key: FragmentKey): Fragment? = fragments[key]

        override fun save(fragment: Fragment): Fragment {
            fragments[fragment.key] = fragment
            return fragment
        }
    }

    /**
     * 12ステージ・実Engine・Fakeを結線したテスト用フィクスチャ。デフォルトは全ステージが
     * 成功する構成とし、各テストは異常系を再現したい箇所のみFakeを差し替える。
     *
     * 引数が多いのは、12ステージ分の依存を1テストごとに差し替え可能にするための
     * 意図的なテスト専用フィクスチャであるため（本番の配線はP9 `prompt-engine-bootstrap`が
     * 個別のConfigurationクラスで行う）。
     */
    @Suppress("LongParameterList")
    private inner class Fixture(
        val promptRepository: FakePromptRepository = FakePromptRepository(),
        val templateRepository: FakeTemplateRepository = FakeTemplateRepository(),
        val fragmentRepository: FakeFragmentRepository = FakeFragmentRepository(),
        validationRules: List<ValidationRule> = emptyList(),
        outputFormatters: Map<OutputFormat, OutputFormatter> = mapOf(OutputFormat.TEXT to TextOutputFormatter()),
        executionAdapter: ExecutionAdapter = SuccessExecutionAdapter(),
        val eventBusAdapter: EventBusAdapter = RecordingEventBusAdapter(),
        val auditRepository: AuditRepository = RecordingAuditRepository(),
        val auditFailureHandler: RecordingAuditFailureHandler = RecordingAuditFailureHandler(),
        val tracer: RecordingPipelineTracer = RecordingPipelineTracer(),
    ) {
        private val compositionService = CompositionServiceImpl(templateRepository, fragmentRepository)
        private val variableResolverChain = VariableResolverChainImpl.standard(NoSecretsManagerAdapter)
        private val contextResolverChain = ContextResolverImpl(emptyList())
        private val validationEngine = ValidationEngineImpl(validationRules)
        private val optimizationEngine = OptimizationEngineImpl(emptyList(), tokenizer)
        private val renderEngine = RenderEngineImpl(DefaultTemplateEngine(), tokenizer, outputFormatters)
        private val executionEngine = ExecutionCoordinator(executionAdapter, outputFormatters, tokenizer)

        /** テストが差し替えていない既定の[RecordingAuditRepository]へアクセスするヘルパー。 */
        val recordingAuditRepository: RecordingAuditRepository
            get() = auditRepository as RecordingAuditRepository

        /** テストが差し替えていない既定の[RecordingEventBusAdapter]へアクセスするヘルパー。 */
        val recordingEventBusAdapter: RecordingEventBusAdapter
            get() = eventBusAdapter as RecordingEventBusAdapter

        init {
            promptRepository.put(publishedPrompt(defaultKey))
        }

        fun publishedPrompt(
            key: PromptKey,
            bodyText: String = defaultBody,
            variables: List<VariableDefinition> = listOf(productNameVariable),
            extends: ExtendsRef? = null,
            contextRequirements: List<ContextRequirement> = emptyList(),
        ): Prompt {
            val newVersion =
                NewPromptVersion(
                    SemVer(1, 0, 0),
                    PromptContent(wrap(key.value, "prompt", bodyText)),
                    variables = variables,
                    contextRequirements = contextRequirements,
                    extends = extends,
                )
            val semVer = SemVer(1, 0, 0)
            val (created, _) = Prompt.create(key, newVersion, eventContext)
            val inReview = created.submitForReview(semVer, validationPassed = true)
            val approved = inReview.approve(semVer, approvalCount = 1, requiredApprovalCount = 1)
            return approved.publish(semVer, allDependenciesPublished = true, eventContext).first
        }

        fun baseRequest(
            promptKey: PromptKey = defaultKey,
            variableResolution: PromptRequest =
                PromptRequest(explicitParameters = mapOf("productName" to "Acme Widget")),
            budget: TokenCount = generousBudget,
            executionPolicy: ExecutionPolicy? = null,
        ): PipelineRequest =
            PipelineRequest(
                promptKey = promptKey,
                versionRef = VersionRef.Latest,
                variableResolution = variableResolution,
                modelProfile = modelProfile,
                budget = budget,
                executionPolicy = executionPolicy,
            )

        fun orchestrator(): PipelineOrchestrator {
            val stages =
                listOf(
                    LoadStage(promptRepository, FakePromptAliasRepository()),
                    MergeStage(compositionService),
                    ImportStage(),
                    ResolveVariablesStage(variableResolverChain),
                    ResolveContextStage(contextResolverChain),
                    ValidationStage(validationEngine),
                    OptimizationStage(optimizationEngine),
                    RenderingStage(renderEngine),
                    ExecutionStage(executionEngine),
                    ResponseParsingStage(),
                    EvaluationStage(eventBusAdapter),
                    AuditStage(auditRepository, auditFailureHandler),
                )
            return PipelineOrchestrator(
                PipelineFactory(stages),
                AuditStage(auditRepository, auditFailureHandler),
                tracer,
            )
        }
    }

    private object NoSecretsManagerAdapter : promptengine.domain.variable.SecretManagerAdapter {
        override fun getSecret(name: String): SensitiveValue? = null
    }

    private companion object {
        fun wrap(
            key: String,
            kind: String,
            bodyText: String,
        ): String = "---\npe: \"1\"\nkind: $kind\nkey: $key\n---\n${bodyText.trimIndent()}"
    }
}
