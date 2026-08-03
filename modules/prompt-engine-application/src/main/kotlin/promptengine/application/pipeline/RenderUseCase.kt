package promptengine.application.pipeline

import promptengine.application.command.sha256
import promptengine.domain.pipeline.PipelineMode
import promptengine.domain.pipeline.PipelineRequest
import promptengine.domain.shared.IdempotentCommandExecutor

/**
 * `POST /prompts/{key}/render`（設計書§13.1・§13.2、Render-only、Stage1〜8）。P8の
 * [PipelineOrchestrator]を呼ぶ薄いUseCase。DB外部I/Oのみで完結するため
 * [IdempotentCommandExecutor.executeInTransaction]を使う。
 *
 * [RenderResult]は§13.2のレスポンス例のうち単純型のみを転記したもの。`messages`本文自体は
 * idempotency記録の対象にせず件数のみ保持する（`prompt-engine-interface`実装時に、
 * `PipelineContext.rendered`から完全なDTOへ改めてマッピングする想定。CLAUDE.md
 * 「確認質問はせず設計書に基づいて実装を進める」の対象外＝未実装のInterface層に委ねる部分）。
 */
data class RenderCommand(
    val request: PipelineRequest,
    val traceId: String,
    val idempotencyKey: String? = null,
) {
    // クラス名を先頭に含める: 他のCommandとfingerprintPayload()の結果が衝突しないため（レビュー指摘）。
    internal fun fingerprintPayload(): String = "${this::class.simpleName}:$request"
}

/** [RenderUseCase.handle]の結果。 */
data class RenderResult(
    val promptKey: String,
    val traceId: String,
    val outputFormat: String?,
    val tokenEstimate: Int?,
    val renderHash: String?,
    val messageCount: Int,
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
                traceId = context.traceId,
                outputFormat = rendered?.outputFormat?.name,
                tokenEstimate = rendered?.tokenEstimate?.value,
                renderHash = rendered?.renderHash,
                messageCount = rendered?.messages?.size ?: 0,
            )
        }
}
