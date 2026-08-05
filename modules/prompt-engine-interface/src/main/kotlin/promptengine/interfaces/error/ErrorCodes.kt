package promptengine.interfaces.error

import org.springframework.http.HttpStatus
import promptengine.application.pipeline.StageErrorMapper

/**
 * 設計書§13.3のエラーコード表(HTTP↔code対応)。[StageErrorMapper]（P8、`prompt-engine-application`）
 * が例外→コード文字列の写像を担い（ADR-0015決定4・ADR-0021、唯一の集約点）、本Objectは
 * そのコード文字列→HTTPステータスの対応のみを持つ。CRUD系ハンドラが投げる、
 * [StageErrorMapper]の対象外の例外（`InvalidStateTransitionException`・
 * `IdempotencyKeyConflictException`等）に対応するコード定数もここに追加する
 * （[StageErrorMapper]はPipeline Stage例外専用であり、これらはその対象外であるため）。
 */
object ErrorCodes {
    const val UNAUTHENTICATED = "UNAUTHENTICATED"
    const val PERMISSION_DENIED = "PERMISSION_DENIED"

    /**
     * 設計書§13.3の表には無い（同表は「マッチしたエンドポイントの処理中に起きたエラー」の
     * みを扱い、URLパスがどのエンドポイントにもマッチしないケースは対象外）。
     * `NoResourceFoundException`（9c初回のE2E確認で発覚。従来500に丸められていた）専用。
     */
    const val NOT_FOUND = "NOT_FOUND"
    const val VERSION_NOT_FOUND = "VERSION_NOT_FOUND"
    const val INVALID_STATE_TRANSITION = "INVALID_STATE_TRANSITION"
    const val VERSION_CONFLICT = "VERSION_CONFLICT"
    const val DUPLICATE_KEY = "DUPLICATE_KEY"
    const val IDEMPOTENCY_KEY_CONFLICT = "IDEMPOTENCY_KEY_CONFLICT"
    const val IDEMPOTENCY_KEY_IN_PROGRESS = "IDEMPOTENCY_KEY_IN_PROGRESS"
    const val RATE_LIMITED = "RATE_LIMITED"

    /**
     * [code]（[StageErrorMapper]の定数、または本Objectの定数）に対応するHTTPステータス
     * （設計書§13.3の表そのもの）。未知のコードは`INTERNAL_ERROR`と同じ500として扱う
     * （表に無いコードが誤って生成された場合の安全側フォールバック）。
     */
    fun statusFor(code: String): HttpStatus =
        when (code) {
            StageErrorMapper.VALIDATION_FAILED,
            StageErrorMapper.PARSE_FAILED,
            StageErrorMapper.INVALID_REQUEST,
            StageErrorMapper.CIRCULAR_DEPENDENCY,
            StageErrorMapper.COMPOSITION_LIMIT_EXCEEDED,
            -> HttpStatus.BAD_REQUEST

            UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED
            PERMISSION_DENIED -> HttpStatus.FORBIDDEN

            NOT_FOUND,
            StageErrorMapper.PROMPT_NOT_FOUND,
            VERSION_NOT_FOUND,
            StageErrorMapper.TEMPLATE_NOT_FOUND,
            StageErrorMapper.FRAGMENT_NOT_FOUND,
            -> HttpStatus.NOT_FOUND

            INVALID_STATE_TRANSITION,
            VERSION_CONFLICT,
            DUPLICATE_KEY,
            IDEMPOTENCY_KEY_CONFLICT,
            IDEMPOTENCY_KEY_IN_PROGRESS,
            -> HttpStatus.CONFLICT

            StageErrorMapper.VARIABLE_UNRESOLVED,
            StageErrorMapper.CONTEXT_UNAVAILABLE,
            StageErrorMapper.TOKEN_BUDGET_EXCEEDED,
            -> HttpStatus.UNPROCESSABLE_ENTITY

            RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS
            StageErrorMapper.EXECUTION_FAILED -> HttpStatus.BAD_GATEWAY
            StageErrorMapper.RENDER_ERROR, StageErrorMapper.INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR
            else -> HttpStatus.INTERNAL_SERVER_ERROR
        }
}
