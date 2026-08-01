package promptengine.domain.pipeline

import promptengine.domain.execution.ExecutionPolicy
import promptengine.domain.optimization.ModelProfile
import promptengine.domain.parsing.OutputSchema
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.VersionRef
import promptengine.domain.render.OutputFormat
import promptengine.domain.shared.PromptRequest
import promptengine.domain.shared.TokenCount

/**
 * Pipeline呼出時の入力（設計書§3.4疑似コード`PipelineContext.request`、ADR-0015決定3）。
 *
 * [variableResolution]はVariable/Context解決（Stage 4・5）の入力（既存の[PromptRequest]、
 * ADR-0011決定4をそのまま再利用）。[outputFormat]/[outputSchema]は呼出側が明示指定した
 * 場合のみ設定し、Stage 8（Rendering）で`CompiledPrompt.output`（ADR-0015決定9）より
 * 優先する。[executionPolicy]はFULL_EXECUTIONモードでのみ必須（Stage 9が使う。
 * RENDER_ONLY/COMPILE_ONLYでは未使用のため`null`のままでよい）。
 */
data class PipelineRequest(
    val promptKey: PromptKey,
    val versionRef: VersionRef,
    val variableResolution: PromptRequest,
    val modelProfile: ModelProfile,
    val budget: TokenCount,
    val outputFormat: OutputFormat? = null,
    val outputSchema: OutputSchema? = null,
    val executionPolicy: ExecutionPolicy? = null,
)
