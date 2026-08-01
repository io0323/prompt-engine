package promptengine.application.pipeline

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.composition.CompiledPrompt
import promptengine.domain.context.ContextBindingSet
import promptengine.domain.execution.ExecutionPolicy
import promptengine.domain.optimization.ModelProfile
import promptengine.domain.parsing.OutputFormatter
import promptengine.domain.parsing.OutputSchema
import promptengine.domain.parsing.ParsedOutput
import promptengine.domain.pipeline.PipelineContext
import promptengine.domain.pipeline.PipelineMode
import promptengine.domain.pipeline.PipelineRequest
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.VersionRef
import promptengine.domain.render.OutputDeclaration
import promptengine.domain.render.OutputFormat
import promptengine.domain.shared.Cost
import promptengine.domain.shared.PromptRequest
import promptengine.domain.shared.TokenCount
import promptengine.domain.template.ast.BlockNode
import promptengine.domain.template.ast.BlockRole
import promptengine.domain.template.ast.TextNode
import promptengine.domain.tokenizer.TokenizerPlugin
import promptengine.domain.variable.BindingSet
import promptengine.engine.formatter.TextOutputFormatter
import promptengine.engine.render.DefaultTemplateEngine
import promptengine.engine.render.RenderEngineImpl

/**
 * [RenderingStage]の分岐カバレッジを埋める単体テスト。
 *
 * `context.request.outputFormat ?: compiled.output?.format ?: OutputFormat.TEXT`
 * （ADR-0015決定9の優先順位）の3分岐は、`PipelineFactory`が保証するステージ順序
 * （RENDER_ONLY/FULL_EXECUTIONは必ずStage4・5の後にStage7・8を実行する）の下では
 * `PipelineOrchestrator`経由では3分岐のうち1つしか自然に踏まないため、単独呼び出しで
 * 残り2分岐を担保する。
 *
 * `variableBindings`/`contextBindings`が`null`の場合の空既定へのフォールバックは
 * ADR-0015決定4修正により撤去した（[RenderingStage]のKDoc参照）ため、このテストでは
 * 常に空の[BindingSet]/[ContextBindingSet]を明示的に渡す（前段未実行時の
 * fail-fast自体は[PipelineStageGuardsTest]が検証する）。
 */
class ThinStageBranchCoverageTest {
    private val tokenizer = TokenizerPlugin { text -> TokenCount(text.length) }
    private val modelProfile =
        ModelProfile(
            maxContextTokens = TokenCount(1_000),
            tokenizerId = "test-tokenizer",
            costPerToken = Cost(java.math.BigDecimal.ZERO),
        )
    private val compiledBody = listOf(BlockNode(BlockRole.USER, listOf(TextNode("hello"))))

    private object JsonStubFormatter : OutputFormatter {
        override fun format(): OutputFormat = OutputFormat.JSON

        override fun instruction(schema: OutputSchema?): String = ""

        override fun parse(
            raw: String,
            schema: OutputSchema?,
        ): ParsedOutput = ParsedOutput(OutputFormat.JSON, raw = raw)
    }

    private fun context(
        compiled: CompiledPrompt,
        outputFormat: OutputFormat? = null,
    ): PipelineContext =
        PipelineContext(
            request =
                PipelineRequest(
                    promptKey = PromptKey("support/faq"),
                    versionRef = VersionRef.Latest,
                    variableResolution = PromptRequest(),
                    modelProfile = modelProfile,
                    budget = TokenCount(1_000),
                    outputFormat = outputFormat,
                    executionPolicy = ExecutionPolicy(timeoutMs = 1_000),
                ),
            mode = PipelineMode.FULL_EXECUTION,
            traceId = "trace-branch-coverage",
            compiled = compiled,
            variableBindings = BindingSet.empty(),
            contextBindings = ContextBindingSet.empty(),
        )

    private fun renderingStage(): RenderingStage =
        RenderingStage(
            RenderEngineImpl(
                DefaultTemplateEngine(),
                tokenizer,
                mapOf(OutputFormat.TEXT to TextOutputFormatter(), OutputFormat.JSON to JsonStubFormatter),
            ),
        )

    @Test
    fun `outputFormatは呼出パラメータ明示指定が最優先される`() {
        val compiled =
            CompiledPrompt(
                compiledBody,
                emptyList(),
                emptyList(),
                emptyList(),
                output = OutputDeclaration(OutputFormat.JSON),
            )
        val ctx = context(compiled, outputFormat = OutputFormat.TEXT)

        val result = renderingStage().execute(ctx)

        result.rendered!!.outputFormat shouldBe OutputFormat.TEXT
    }

    @Test
    fun `呼出パラメータ未指定時はCompiledPromptのoutput宣言が使われる`() {
        val compiled =
            CompiledPrompt(
                compiledBody,
                emptyList(),
                emptyList(),
                emptyList(),
                output = OutputDeclaration(OutputFormat.JSON),
            )
        val ctx = context(compiled, outputFormat = null)

        val result = renderingStage().execute(ctx)

        result.rendered!!.outputFormat shouldBe OutputFormat.JSON
    }

    @Test
    fun `どちらも未指定ならTEXTが既定になる`() {
        val compiled = CompiledPrompt(compiledBody, emptyList(), emptyList(), emptyList(), output = null)
        val ctx = context(compiled, outputFormat = null)

        val result = renderingStage().execute(ctx)

        result.rendered!!.outputFormat shouldBe OutputFormat.TEXT
    }
}
