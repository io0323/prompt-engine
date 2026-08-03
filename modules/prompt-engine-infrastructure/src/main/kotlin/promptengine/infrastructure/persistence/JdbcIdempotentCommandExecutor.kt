package promptengine.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import promptengine.domain.shared.IdempotencyKeyConflictException
import promptengine.domain.shared.IdempotencyKeyInProgressException
import promptengine.domain.shared.IdempotentCommandExecutor
import java.sql.Timestamp
import java.time.Instant

private const val STATUS_IN_PROGRESS = "IN_PROGRESS"
private const val STATUS_COMPLETED = "COMPLETED"

/**
 * [IdempotentCommandExecutor]のJDBC実装（`idempotency_keys`テーブル、設計書§12、P9b）。
 *
 * [executeInTransaction]（CRUD系）はキー予約・command実行・完了記録を[transactionTemplate]の
 * 1トランザクションで行う。[executeLongRunning]（`execute`等の長時間操作）は予約と完了記録を
 * それぞれ独立した短いトランザクションで行い、[operation]自体はトランザクション外で実行する
 * （APAP呼出等でDBコネクションを長時間保持しないため、P9bレビュー指摘）。
 *
 * 同一キーへの同時挿入は`idempotency_key`のPRIMARY KEY制約により一方が[DuplicateKeyException]
 * となるため、[IdempotencyKeyInProgressException]として扱う（先行リクエストが処理中と見なす）。
 */
class JdbcIdempotentCommandExecutor(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
    private val objectMapper: ObjectMapper,
) : IdempotentCommandExecutor {
    override fun <T : Any> executeInTransaction(
        idempotencyKey: String?,
        requestFingerprint: String,
        resultType: Class<T>,
        command: () -> T,
    ): T {
        if (idempotencyKey == null) return transactionTemplate.execute { command() }!!
        return transactionTemplate.execute {
            when (val outcome = reserveOrResolve(idempotencyKey, requestFingerprint, resultType)) {
                is ReservationOutcome.AlreadyCompleted -> outcome.result
                is ReservationOutcome.Reserved -> {
                    val result = command()
                    markCompleted(idempotencyKey, result, resultType)
                    result
                }
            }
        }!!
    }

    override fun <T : Any> executeLongRunning(
        idempotencyKey: String?,
        requestFingerprint: String,
        resultType: Class<T>,
        operation: () -> T,
    ): T {
        if (idempotencyKey == null) return operation()
        val outcome =
            transactionTemplate.execute {
                reserveOrResolve(idempotencyKey, requestFingerprint, resultType)
            }!!
        return when (outcome) {
            is ReservationOutcome.AlreadyCompleted -> outcome.result
            is ReservationOutcome.Reserved -> {
                val result = runOperationOrReleaseReservation(idempotencyKey, operation)
                transactionTemplate.execute { markCompleted(idempotencyKey, result, resultType) }
                result
            }
        }
    }

    /**
     * [operation]が失敗した場合、`IN_PROGRESS`のまま残った予約行を削除してから例外を再送出する。
     * 削除しないと、以降同一キー・同一fingerprintでの再送が[IdempotencyKeyInProgressException]で
     * 永久にブロックされ、失敗した処理をリトライできなくなる（レビュー指摘、Critical）。
     *
     * **既知の制約（[Issue #50](https://github.com/io0323/prompt-engine/issues/50)）**:
     * この解放処理はJVMプロセスの正常な例外伝播を前提とする。プロセスがクラッシュした場合
     * （OOM Kill・ノード障害等）は`catch`節自体が実行されず、予約が`IN_PROGRESS`のまま残る。
     * 有効期限ベースで期限切れ予約を新規リクエストが奪取できるようにする対応をIssue #50で
     * 追跡する（P10）。
     */
    @Suppress("TooGenericExceptionCaught")
    private fun <T : Any> runOperationOrReleaseReservation(
        idempotencyKey: String,
        operation: () -> T,
    ): T =
        try {
            operation()
        } catch (e: Exception) {
            transactionTemplate.executeWithoutResult { releaseReservation(idempotencyKey) }
            throw e
        }

    private fun releaseReservation(idempotencyKey: String) {
        jdbcTemplate.update(
            "DELETE FROM idempotency_keys WHERE idempotency_key = :idempotencyKey AND status = :status",
            MapSqlParameterSource()
                .addValue("idempotencyKey", idempotencyKey)
                .addValue("status", STATUS_IN_PROGRESS),
        )
    }

    private sealed class ReservationOutcome<out T> {
        data class AlreadyCompleted<T>(val result: T) : ReservationOutcome<T>()

        data object Reserved : ReservationOutcome<Nothing>()
    }

    private fun <T : Any> reserveOrResolve(
        idempotencyKey: String,
        requestFingerprint: String,
        resultType: Class<T>,
    ): ReservationOutcome<T> {
        val existing = findRow(idempotencyKey)
        if (existing == null) {
            insertReserved(idempotencyKey, requestFingerprint)
            return ReservationOutcome.Reserved
        }
        if (existing.requestFingerprint != requestFingerprint) {
            throw IdempotencyKeyConflictException(idempotencyKey)
        }
        return when (existing.status) {
            STATUS_IN_PROGRESS -> throw IdempotencyKeyInProgressException(idempotencyKey)
            STATUS_COMPLETED ->
                ReservationOutcome.AlreadyCompleted(
                    objectMapper.readValue(existing.resultJson, resultType),
                )
            else -> error("unknown idempotency_keys.status: ${existing.status}")
        }
    }

    private fun insertReserved(
        idempotencyKey: String,
        requestFingerprint: String,
    ) {
        try {
            jdbcTemplate.update(
                """
                INSERT INTO idempotency_keys (idempotency_key, request_fingerprint, status, created_at)
                VALUES (:idempotencyKey, :requestFingerprint, :status, :createdAt)
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("idempotencyKey", idempotencyKey)
                    .addValue("requestFingerprint", requestFingerprint)
                    .addValue("status", STATUS_IN_PROGRESS)
                    .addValue("createdAt", Timestamp.from(Instant.now())),
            )
        } catch (e: DuplicateKeyException) {
            throw IdempotencyKeyInProgressException(idempotencyKey, e)
        }
    }

    private fun <T : Any> markCompleted(
        idempotencyKey: String,
        result: T,
        resultType: Class<T>,
    ) {
        jdbcTemplate.update(
            """
            UPDATE idempotency_keys
            SET status = :status, result_type = :resultType, result_json = :resultJson::json, completed_at = :completedAt
            WHERE idempotency_key = :idempotencyKey
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("idempotencyKey", idempotencyKey)
                .addValue("status", STATUS_COMPLETED)
                .addValue("resultType", resultType.name)
                .addValue("resultJson", objectMapper.writeValueAsString(result))
                .addValue("completedAt", Timestamp.from(Instant.now())),
        )
    }

    private fun findRow(idempotencyKey: String): IdempotencyKeyRow? =
        jdbcTemplate
            .query(
                """
                SELECT request_fingerprint, status, result_json
                FROM idempotency_keys
                WHERE idempotency_key = :idempotencyKey
                """.trimIndent(),
                MapSqlParameterSource().addValue("idempotencyKey", idempotencyKey),
            ) { rs, _ ->
                IdempotencyKeyRow(
                    requestFingerprint = rs.getString("request_fingerprint"),
                    status = rs.getString("status"),
                    resultJson = rs.getString("result_json"),
                )
            }.firstOrNull()

    private data class IdempotencyKeyRow(
        val requestFingerprint: String,
        val status: String,
        val resultJson: String?,
    )
}
