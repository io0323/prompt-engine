package promptengine.application.error

import promptengine.application.pipeline.StageErrorMapper
import promptengine.domain.benchmark.BenchmarkNotFoundException
import promptengine.domain.benchmark.GoldenDatasetNotFoundException
import promptengine.domain.experiment.ExperimentNotFoundException
import promptengine.domain.experiment.NoWinnerDeclaredException
import promptengine.domain.prompt.ArchiveRequiresForceException
import promptengine.domain.shared.IdempotencyKeyConflictException
import promptengine.domain.shared.IdempotencyKeyInProgressException
import promptengine.domain.shared.OptimisticLockConflictException
import promptengine.domain.validation.ValidationFailedException

/** 設計書§13.3 `details[]`の1件。`prompt-engine-interface`はdomain型（`Finding`等）に依存できないため、この型経由で受け渡す。 */
data class ErrorDetail(val rule: String, val path: String, val severity: String, val message: String)

/**
 * 例外→エラーコードの変換における唯一の呼出口(P9c、`prompt-engine-interface`向け)。
 *
 * `prompt-engine-interface`は`promptengine.domain..`に依存できない（ArchUnitルール、
 * [PromptViews.kt][promptengine.application.view]のKDoc参照）ため、`GlobalExceptionHandler`は
 * 個々のdomain例外型を`@ExceptionHandler`で直接指定できない（Spring/Java標準の例外型のみ
 * 指定可能）。本Objectが型判定を一手に引き受け、`GlobalExceptionHandler`は
 * `resolve(Throwable): String`の戻り値(文字列)のみを扱う。
 *
 * Pipeline Stage由来の例外は[StageErrorMapper]（ADR-0015決定4・ADR-0021、唯一の集約点）へ
 * そのまま委譲する。ここで追加判定するのは、[StageErrorMapper]の対象外であるCRUD系
 * ハンドラの例外（状態遷移ガード・冪等性）のみ。
 */
object ErrorCodeResolver {
    const val INVALID_STATE_TRANSITION = "INVALID_STATE_TRANSITION"
    const val IDEMPOTENCY_KEY_CONFLICT = "IDEMPOTENCY_KEY_CONFLICT"
    const val IDEMPOTENCY_KEY_IN_PROGRESS = "IDEMPOTENCY_KEY_IN_PROGRESS"
    const val VERSION_CONFLICT = "VERSION_CONFLICT"

    /** `promptengine.interfaces.error.ErrorCodes.NOT_FOUND`と同じ文字列値（ADR-0034）。 */
    const val NOT_FOUND = "NOT_FOUND"

    fun resolve(throwable: Throwable): String =
        when (throwable) {
            is promptengine.domain.prompt.InvalidStateTransitionException -> INVALID_STATE_TRANSITION
            is promptengine.domain.shared.InvalidStateTransitionException -> INVALID_STATE_TRANSITION
            is ArchiveRequiresForceException -> INVALID_STATE_TRANSITION
            is NoWinnerDeclaredException -> INVALID_STATE_TRANSITION
            is ExperimentNotFoundException -> NOT_FOUND
            is BenchmarkNotFoundException -> NOT_FOUND
            is GoldenDatasetNotFoundException -> NOT_FOUND
            is IdempotencyKeyConflictException -> IDEMPOTENCY_KEY_CONFLICT
            is IdempotencyKeyInProgressException -> IDEMPOTENCY_KEY_IN_PROGRESS
            is OptimisticLockConflictException -> VERSION_CONFLICT
            else -> StageErrorMapper.errorCodeFor(throwable)
        }

    /** [ValidationFailedException]のみ`report.findings`を[ErrorDetail]へ変換する。その他は空リスト。 */
    fun detailsFor(throwable: Throwable): List<ErrorDetail> =
        when (throwable) {
            is ValidationFailedException ->
                throwable.report.findings.map { ErrorDetail(it.ruleId, it.path, it.severity.name, it.message) }
            else -> emptyList()
        }
}
