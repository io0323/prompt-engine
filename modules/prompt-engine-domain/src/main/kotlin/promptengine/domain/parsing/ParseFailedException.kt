package promptengine.domain.parsing

import promptengine.domain.render.OutputFormat

/**
 * [OutputFormatter.parse]が失敗した（設計書§13.3 `PARSE_FAILED`、ADR-0014決定2・決定9）。
 *
 * [reason]は構造的な理由のみ（フィールド名・エラー種別等）を設定する契約とする。生の
 * `raw`/`content`を含めてはならない（ログ・Audit・例外メッセージへの秘密情報混入を防ぐ）。
 * この契約は型では強制できないため、[OutputFormatter]実装側の責務とする。
 */
class ParseFailedException(
    val format: OutputFormat,
    val reason: String,
    val repairAttempts: Int = 0,
    cause: Throwable? = null,
) : RuntimeException("PARSE_FAILED: $reason (format=$format, repairAttempts=$repairAttempts)", cause)
