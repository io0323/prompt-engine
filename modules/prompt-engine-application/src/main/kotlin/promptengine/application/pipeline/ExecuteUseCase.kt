package promptengine.application.pipeline

import promptengine.application.command.sha256
import promptengine.domain.pipeline.PipelineMode
import promptengine.domain.pipeline.PipelineRequest
import promptengine.domain.shared.IdempotentCommandExecutor

/**
 * `POST /prompts/{key}/execute`（設計書§13.1・§13.2、Full-execution、Stage1〜12）。P8の
 * [PipelineOrchestrator]を呼ぶ薄いUseCase。
 *
 * APAP呼出（Stage 9）を含み数秒〜数十秒かかり得るため、[IdempotentCommandExecutor]の
 * CRUD向け`executeInTransaction`ではなく[IdempotentCommandExecutor.executeLongRunning]を使う
 * （DBトランザクションでコネクションを長時間保持しないため、P9bレビュー指摘）。
 */
data class ExecuteCommand(
    val request: PipelineRequest,
    val traceId: String,
    val idempotencyKey: String? = null,
) {
    // クラス名を先頭に含める: 他のCommandとfingerprintPayload()の結果が衝突しないため（レビュー指摘）。
    internal fun fingerprintPayload(): String = "${this::class.simpleName}:$request"
}

/** [ExecuteUseCase.handle]の結果。§13.2のレスポンス例のうち単純型のみを転記したもの。 */
data class ExecuteResult(
    val promptKey: String,
    val traceId: String,
    val outputFormat: String?,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val latencyMs: Long?,
    val attemptCount: Int,
)

/** [ExecuteCommand]のハンドラ。P8の[PipelineOrchestrator]をFULL_EXECUTIONモードで呼ぶ。 */
class ExecuteUseCase(
    private val pipelineOrchestrator: PipelineOrchestrator,
    private val idempotentCommandExecutor: IdempotentCommandExecutor,
) {
    fun handle(command: ExecuteCommand): ExecuteResult =
        idempotentCommandExecutor.executeLongRunning(
            command.idempotencyKey,
            command.fingerprintPayload().sha256(),
            ExecuteResult::class.java,
        ) {
            val context = pipelineOrchestrator.run(command.request, PipelineMode.FULL_EXECUTION, command.traceId)
            val outcome = context.executionOutcome
            val lastAttempt = outcome?.attempts?.lastOrNull()
            ExecuteResult(
                promptKey = command.request.promptKey.value,
                traceId = context.traceId,
                outputFormat = outcome?.parsedOutput?.format?.name,
                inputTokens = lastAttempt?.usage?.inputTokens?.value,
                outputTokens = lastAttempt?.usage?.outputTokens?.value,
                latencyMs = lastAttempt?.latency?.value,
                attemptCount = outcome?.attempts?.size ?: 0,
            )
        }
}
