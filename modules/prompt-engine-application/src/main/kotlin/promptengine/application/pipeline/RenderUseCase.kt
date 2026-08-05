package promptengine.application.pipeline

import promptengine.application.command.sha256
import promptengine.domain.pipeline.PipelineMode
import promptengine.domain.pipeline.PipelineRequest
import promptengine.domain.shared.IdempotentCommandExecutor
import promptengine.domain.validation.Severity

/**
 * `POST /prompts/{key}/render`（設計書§13.1・§13.2、Render-only、Stage1〜8）。P8の
 * [PipelineOrchestrator]を呼ぶ薄いUseCase。DB外部I/Oのみで完結するため
 * [IdempotentCommandExecutor.executeInTransaction]を使う。
 *
 * [RenderResult]は§13.2のレスポンス例と完全一致するフィールドを持つ（P9c、
 * `prompt-engine-interface`実装時に確定）。`messages`本文自体を含めて`IdempotentCommandExecutor`
 * が永続化する結果に含める: 同一Idempotency-Keyでの再送時にクライアントへ返す内容は
 * 初回応答と同一でなければならず、`PipelineContext`自体（`compiled`等の内部専用型を含む）は
 * Jackson往復を前提としないため、往復可能な単純型へ要約したこの型を記録対象にする
 * 必要がある（P9bの暫定コメントを本フェーズで解消）。
 */
data class RenderCommand(
    val request: PipelineRequest,
    val traceId: String,
    val idempotencyKey: String? = null,
) {
    // クラス名を先頭に含める: 他のCommandとfingerprintPayload()の結果が衝突しないため（レビュー指摘）。
    internal fun fingerprintPayload(): String = "${this::class.simpleName}:$request"
}

/** [RenderResult.messages]の1件（設計書§13.2 `{role, content}`）。 */
data class RenderedMessageSummary(val role: String, val content: String)

/** [RenderResult.warnings]の1件（設計書§13.3 `details[]`と同形。WARNING severityのFindingのみ）。 */
data class FindingSummary(val ruleId: String, val path: String, val severity: String, val message: String)

/** [RenderUseCase.handle]の結果。設計書§13.2のRenderレスポンス例と1:1対応する。 */
data class RenderResult(
    val promptKey: String,
    val version: String?,
    val traceId: String,
    val messages: List<RenderedMessageSummary>,
    val outputFormat: String?,
    val outputSchemaRef: String?,
    val tokenEstimate: Int?,
    val renderHash: String?,
    val warnings: List<FindingSummary>,
)

/** [RenderCommand]のハンドラ。P8の[PipelineOrchestrator]をRENDER_ONLYモードで呼ぶ。 */
class RenderUseCase(
    private val pipelineOrchestrator: PipelineOrchestrator,
    private val idempotentCommandExecutor: IdempotentCommandExecutor,
) {
    fun handle(command: RenderCommand): RenderResult =
        idempotentCommandExecutor.executeInTransaction(
            command.idempotencyKey,
            command.fingerprintPayload().sha256(),
            RenderResult::class.java,
        ) {
            val context = pipelineOrchestrator.run(command.request, PipelineMode.RENDER_ONLY, command.traceId)
            val rendered = context.rendered
            RenderResult(
                promptKey = command.request.promptKey.value,
                version = context.promptVersion?.semVer?.toString(),
                traceId = context.traceId,
                messages =
                    rendered?.messages?.map { RenderedMessageSummary(it.role.name, it.content) } ?: emptyList(),
                outputFormat = rendered?.outputFormat?.name,
                outputSchemaRef = context.promptVersion?.output?.schemaRef,
                tokenEstimate = rendered?.tokenEstimate?.value,
                renderHash = rendered?.renderHash,
                warnings =
                    context.validationReport?.findings
                        ?.filter { it.severity == Severity.WARNING }
                        ?.map { FindingSummary(it.ruleId, it.path, it.severity.name, it.message) }
                        ?: emptyList(),
            )
        }
}
