package promptengine.infrastructure.persistence

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import promptengine.domain.dlq.DeadLetterEntry
import promptengine.domain.dlq.DeadLetterQueueRepository
import java.sql.Timestamp

/**
 * [DeadLetterQueueRepository]のJDBC実装（`dead_letter_queue`、Issue #37、ADR-0026決定2）。
 *
 * ## 退避の検知
 * ADR-0026決定2の通り、新しいメトリクスバックエンド（Micrometer等）を本PRでは導入しない。
 * 代わりに2本立てで検知可能にする:
 * 1. 退避1件ごとの構造化ERRORログ（`dead_letter_enqueued`）。[promptengine.infrastructure.audit.Slf4jAuditFailureHandler]と
 *    同じ方針で、`Throwable`オブジェクトも例外メッセージ本文もログ経路へ渡さない
 *    （インフラ層由来の例外メッセージに接続情報等が混ざりうるため）。
 * 2. [pendingCount]による未処理件数の問い合わせ（監視側がポーリングしてゲージ化できる）。
 *
 * ## 書き込み失敗を伝播させない
 * [enqueue]はDLQ書き込み自体が失敗しても例外を投げない。DLQへの書き込み失敗が本流の
 * イベント処理を巻き込んで落とすと、DLQを設けた意味が失われるため（domain側の契約）。
 * その場合も`dead_letter_enqueue_failed`としてログには必ず残す。
 */
class JdbcDeadLetterQueueRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
) : DeadLetterQueueRepository {
    @Suppress("TooGenericExceptionCaught")
    override fun enqueue(entry: DeadLetterEntry) {
        try {
            insert(entry)
            logger.error(
                "dead_letter_enqueued eventId={} eventType={} subscriber={} reason={}",
                entry.eventId,
                entry.eventType,
                entry.subscriberName,
                entry.failureReason,
            )
        } catch (e: Exception) {
            // DLQはこれ以上退避先が無い最終地点。ここで例外を投げると本流を落とすため、
            // ログのみ残して飲み込む（domain DeadLetterQueueRepositoryの契約）。
            logger.error(
                "dead_letter_enqueue_failed eventId={} eventType={} subscriber={} cause={}",
                entry.eventId,
                entry.eventType,
                entry.subscriberName,
                e.javaClass.simpleName,
            )
        }
    }

    private fun insert(entry: DeadLetterEntry) {
        jdbcTemplate.update(
            """
            INSERT INTO dead_letter_queue
                (event_id, event_type, subscriber_name, payload, failure_reason,
                 first_failed_at, last_failed_at, retry_count, status)
            VALUES
                (:eventId, :eventType, :subscriberName, :payload::json, :failureReason,
                 :failedAt, :failedAt, 0, 'PENDING')
            ON CONFLICT (event_id, subscriber_name) DO UPDATE SET
                retry_count = dead_letter_queue.retry_count + 1,
                last_failed_at = EXCLUDED.last_failed_at,
                failure_reason = EXCLUDED.failure_reason
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("eventId", entry.eventId)
                .addValue("eventType", entry.eventType)
                .addValue("subscriberName", entry.subscriberName)
                .addValue("payload", entry.payload)
                .addValue("failureReason", entry.failureReason)
                .addValue("failedAt", Timestamp.from(entry.failedAt)),
        )
    }

    override fun pendingCount(): Long =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM dead_letter_queue WHERE status = 'PENDING'",
            MapSqlParameterSource(),
            Long::class.java,
        ) ?: 0L

    private companion object {
        val logger = LoggerFactory.getLogger(JdbcDeadLetterQueueRepository::class.java)
    }
}
