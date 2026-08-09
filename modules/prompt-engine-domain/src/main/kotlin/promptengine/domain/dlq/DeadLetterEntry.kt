package promptengine.domain.dlq

import java.time.Instant
import java.util.UUID

/**
 * DLQ（`dead_letter_queue`、Issue #37・ADR-0026決定2）へ退避する1件（ADR-0026決定2）。
 *
 * [eventId]は退避元がBroker由来の場合の`event_id`。Pipeline Stage 12（Audit）の
 * `AuditRecord`退避経路はキーにできるイベントを持たないため`null`になる。
 *
 * [payload]はSecretマスク済のJSON文字列であることを呼出側が保証する（CLAUDE.md
 * 「Secret/sensitive=trueの変数値は絶対に出力しない」。DLQは運用者が中身を目視する
 * 前提のテーブルであり、監査ログと同じ厳しさを要求する）。
 *
 * [failureReason]には例外クラス名など**構造的な情報のみ**を入れ、例外メッセージ本文を
 * そのまま入れない（`Slf4jAuditFailureHandler`が確立した方針: インフラ層由来の例外
 * メッセージには接続情報等の秘密が混ざりうる）。
 */
data class DeadLetterEntry(
    val eventId: UUID?,
    val eventType: String,
    val subscriberName: String,
    val payload: String,
    val failureReason: String,
    val failedAt: Instant,
) {
    init {
        require(eventType.isNotBlank()) { "eventType must not be blank" }
        require(subscriberName.isNotBlank()) { "subscriberName must not be blank" }
        require(failureReason.isNotBlank()) { "failureReason must not be blank" }
    }
}
