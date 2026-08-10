package promptengine.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import promptengine.domain.shared.IdempotencyKeyConflictException
import promptengine.domain.shared.IdempotencyKeyInProgressException
import promptengine.domain.shared.IdempotentCommandExecutor
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

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
 *
 * **クレーム所有トークン＋タイムアウトベースの奪取＋フェンシング（Issue #50、ADR-0027）**:
 * `IN_PROGRESS`予約はADR-0025のOutbox中継と同型の3段階Claim方式（同ADR §3）で保護する。
 * [reserveOrResolve]が新規予約（[insertReserved]）または期限切れ予約の奪取のいずれかで
 * 都度[UUID.randomUUID]による新しい所有トークンを発行し、[markCompleted]/[releaseReservation]
 * （書き戻しフェーズ）はそのトークンが一致する行にのみ作用する`WHERE claimed_by = :token`条件
 * （フェンシング）を課す。Outboxはバックグラウンドポーラーがキューを能動的に排出する必要が
 * あるためタイムアウト監視も定期実行するが、`idempotency_keys`は「同一キーでの再送」が
 * 起きたときにのみ意味を持ち排出すべきキューが無いため、バックグラウンドスイーパーは
 * 持たない。代わりに、同一キーでの次回リクエストが[reserveOrResolve]内でinlineに
 * 期限切れ予約を奪取する（trigger機構のみがOutboxと異なり、Claim/Fencingの3段階自体は
 * 同型）。所有トークンは呼び出し単位（1回の[executeLongRunning]呼出＝1オーナー）で
 * インライン生成し、Outboxの`instanceId`のようなBeanレベルの共有トークンは使わない
 * （1呼出のcall stack内で完結するため不要）。
 *
 * クレーム奪取・フェンシングの追加でdetektの関数数閾値をわずかに超える
 * （`EventStorePromptRepository`と同じ判断）。`reserveOrResolve`/`resolveInProgress`/
 * `tryReclaim`/`markCompleted`/`releaseReservation`/`logFencingLost`は互いに強く結合した
 * 1つのクレーム状態機械であり、恣意的に別クラスへ分割すると呼出順序・フェンシング条件の
 * 対応関係がかえって追いにくくなる。
 */
@Suppress("TooManyFunctions")
class JdbcIdempotentCommandExecutor(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
    private val objectMapper: ObjectMapper,
    private val claimTimeoutSeconds: Long = DEFAULT_CLAIM_TIMEOUT_SECONDS,
) : IdempotentCommandExecutor {
    /**
     * キー予約・[command]実行・完了記録を[transactionTemplate]の1トランザクションで行う。
     *
     * このパス**自身**はクラッシュで`IN_PROGRESS`行を残さない: 予約INSERTから完了UPDATEまでが
     * 常に単一の未コミットトランザクション内で完結するため、プロセスがクラッシュした場合は
     * Postgres自体がトランザクション全体をロールバックする（[executeLongRunning]と異なり、
     * [command]をトランザクション外で実行する必要が無いCRUD系コマンド専用のため）。
     *
     * ただし、このメソッドは[executeLongRunning]と同じ[reserveOrResolve]を経由するため、
     * 同一`idempotencyKey`の行が[executeLongRunning]呼出やV14マイグレーションのバックフィルで
     * 既に`IN_PROGRESS`かつ期限切れのまま残っている場合、このメソッドの呼出がその行を
     * 奪取（[resolveInProgress]・[tryReclaim]）することがある（CodeRabbitレビュー指摘:
     * 「このパスはクレーム奪取・フェンシングの対象外」という以前の記述は誤りだった。
     * 対象外なのは「自身がIN_PROGRESS行を残すこと」のみで、「他所が残した行を奪取すること」
     * には参加する）。
     */
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
                    markCompleted(idempotencyKey, outcome.claimToken, result, resultType)
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
                val result = runOperationOrReleaseReservation(idempotencyKey, outcome.claimToken, operation)
                transactionTemplate.execute { markCompleted(idempotencyKey, outcome.claimToken, result, resultType) }
                result
            }
        }
    }

    /**
     * [operation]が失敗した場合、`IN_PROGRESS`のまま残った予約行を削除してから例外を再送出する。
     * 削除しないと、以降同一キー・同一fingerprintでの再送が[IdempotencyKeyInProgressException]で
     * 永久にブロックされ、失敗した処理をリトライできなくなる（レビュー指摘、Critical）。
     *
     * プロセスがクラッシュして本catch節自体が実行されなかった場合は、予約が`IN_PROGRESS`の
     * まま残るが、[claimTimeoutSeconds]経過後は同一キーへの次回リクエストが
     * [reserveOrResolve]の奪取ロジックで安全に再クレームする（Issue #50、ADR-0027）。
     */
    @Suppress("TooGenericExceptionCaught")
    private fun <T : Any> runOperationOrReleaseReservation(
        idempotencyKey: String,
        claimToken: String,
        operation: () -> T,
    ): T =
        try {
            operation()
        } catch (e: Exception) {
            transactionTemplate.executeWithoutResult { releaseReservation(idempotencyKey, claimToken) }
            throw e
        }

    /**
     * [claimToken]が一致する行にのみ作用する（フェンシング、ADR-0027）。奪取タイムアウト経過後に
     * 別リクエストがこの予約を再クレームしていた場合、この呼出の[claimToken]は既に古いため
     * 0行影響となる。その場合は例外にせず[logFencingLost]でWARNを出すのみとする
     * （このリクエスト自身の[operation]は既に失敗しており、削除できてもできなくても
     * 呼出元へは同じ例外を再送出するため、フェンシング喪失を追加のエラーにする意味が無い）。
     */
    private fun releaseReservation(
        idempotencyKey: String,
        claimToken: String,
    ) {
        val deleted =
            jdbcTemplate.update(
                """
                DELETE FROM idempotency_keys
                WHERE idempotency_key = :idempotencyKey AND status = :status AND claimed_by = :claimedBy
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("idempotencyKey", idempotencyKey)
                    .addValue("status", STATUS_IN_PROGRESS)
                    .addValue("claimedBy", claimToken),
            )
        if (deleted == 0) logFencingLost(idempotencyKey, "releaseReservation", claimToken)
    }

    private sealed class ReservationOutcome<out T> {
        data class AlreadyCompleted<T>(val result: T) : ReservationOutcome<T>()

        /** [claimToken]は、この予約（新規または奪取）に対して発行された所有トークン（Issue #50、ADR-0027）。 */
        data class Reserved(val claimToken: String) : ReservationOutcome<Nothing>()
    }

    private fun <T : Any> reserveOrResolve(
        idempotencyKey: String,
        requestFingerprint: String,
        resultType: Class<T>,
    ): ReservationOutcome<T> {
        val existing = findRow(idempotencyKey)
        if (existing == null) {
            val claimToken = UUID.randomUUID().toString()
            insertReserved(idempotencyKey, requestFingerprint, claimToken)
            return ReservationOutcome.Reserved(claimToken)
        }
        if (existing.requestFingerprint != requestFingerprint) {
            throw IdempotencyKeyConflictException(idempotencyKey)
        }
        return when (existing.status) {
            STATUS_IN_PROGRESS -> resolveInProgress(idempotencyKey, existing)
            STATUS_COMPLETED ->
                ReservationOutcome.AlreadyCompleted(
                    objectMapper.readValue(existing.resultJson, resultType),
                )
            else -> error("unknown idempotency_keys.status: ${existing.status}")
        }
    }

    /**
     * `IN_PROGRESS`の既存行に対する処理（Issue #50、ADR-0027）。[IdempotencyKeyRow.claimedAt]が
     * [claimTimeoutSeconds]以内なら、まだ正当に実行中の可能性があるため従来通り
     * [IdempotencyKeyInProgressException]を投げる。期限切れなら[tryReclaim]で奪取を試みる。
     *
     * [IdempotencyKeyRow.claimedAt]が`null`のケース（理論上は本マイグレーション適用後は
     * 発生しない想定。[insertReserved]は常に`claimed_at`を設定し、既存滞留行はV14マイグレーション
     * で必ずバックフィルされる）は、安全側に倒して「期限切れではない」とみなし従来通り
     * 例外を投げる。
     */
    private fun <T> resolveInProgress(
        idempotencyKey: String,
        existing: IdempotencyKeyRow,
    ): ReservationOutcome<T> {
        val claimedAt = existing.claimedAt
        val isStale = claimedAt != null && claimedAt.isBefore(Instant.now().minusSeconds(claimTimeoutSeconds))
        if (!isStale) throw IdempotencyKeyInProgressException(idempotencyKey)
        val newClaimToken = UUID.randomUUID().toString()
        val reclaimed = tryReclaim(idempotencyKey, existing.claimedBy, newClaimToken)
        if (!reclaimed) throw IdempotencyKeyInProgressException(idempotencyKey)
        return ReservationOutcome.Reserved(newClaimToken)
    }

    /**
     * 期限切れ`IN_PROGRESS`予約の奪取（Issue #50、ADR-0027）。[oldClaimedBy]（奪取直前に読んだ
     * `claimed_by`）と一致する行にのみ作用する条件付きUPDATEにより、複数リクエストが同時に
     * 同じ期限切れ予約を奪取しようとしても1件のみが成功する（3段階Claim方式のクレームフェーズ）。
     * 0行影響（既に他リクエストが奪取済み）の場合はfalseを返し、呼出元は
     * [IdempotencyKeyInProgressException]にフォールバックする（ループ/リトライしない。
     * クライアント自身の再送で解消される想定の、単純で安全なスプリアス409）。
     */
    private fun tryReclaim(
        idempotencyKey: String,
        oldClaimedBy: String?,
        newClaimToken: String,
    ): Boolean {
        val params =
            MapSqlParameterSource()
                .addValue("idempotencyKey", idempotencyKey)
                .addValue("status", STATUS_IN_PROGRESS)
                .addValue("newClaimedAt", Timestamp.from(Instant.now()))
                .addValue("newClaimedBy", newClaimToken)
        val claimedByCondition =
            if (oldClaimedBy == null) {
                "claimed_by IS NULL"
            } else {
                params.addValue("oldClaimedBy", oldClaimedBy)
                "claimed_by = :oldClaimedBy"
            }
        val updated =
            jdbcTemplate.update(
                """
                UPDATE idempotency_keys
                SET claimed_at = :newClaimedAt, claimed_by = :newClaimedBy
                WHERE idempotency_key = :idempotencyKey AND status = :status AND $claimedByCondition
                """.trimIndent(),
                params,
            )
        return updated == 1
    }

    private fun insertReserved(
        idempotencyKey: String,
        requestFingerprint: String,
        claimToken: String,
    ) {
        try {
            val now = Timestamp.from(Instant.now())
            jdbcTemplate.update(
                """
                INSERT INTO idempotency_keys
                    (idempotency_key, request_fingerprint, status, created_at, claimed_at, claimed_by)
                VALUES (:idempotencyKey, :requestFingerprint, :status, :createdAt, :claimedAt, :claimedBy)
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("idempotencyKey", idempotencyKey)
                    .addValue("requestFingerprint", requestFingerprint)
                    .addValue("status", STATUS_IN_PROGRESS)
                    .addValue("createdAt", now)
                    .addValue("claimedAt", now)
                    .addValue("claimedBy", claimToken),
            )
        } catch (e: DuplicateKeyException) {
            throw IdempotencyKeyInProgressException(idempotencyKey, e)
        }
    }

    /**
     * [claimToken]が一致する行にのみ作用する（フェンシング、ADR-0027）。0行影響の場合、
     * [operation]は既に成功しCallerには正しい[result]が返せる状態にあるため、例外にはせず
     * [logFencingLost]でWARNを出すのみとする。この呼出はクレームの奪取レースに敗れて
     * 「正準の完了行」としては記録されなかっただけで、この呼出自身の処理は正当に成功しており、
     * 呼出元のHTTPリクエストへは成功として返す（奪取した側が自身の完了を別途記録する）。
     */
    private fun <T : Any> markCompleted(
        idempotencyKey: String,
        claimToken: String,
        result: T,
        resultType: Class<T>,
    ) {
        val updated =
            jdbcTemplate.update(
                """
                UPDATE idempotency_keys
                SET status = :status, result_type = :resultType, result_json = :resultJson::json, completed_at = :completedAt
                WHERE idempotency_key = :idempotencyKey AND claimed_by = :claimedBy
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("idempotencyKey", idempotencyKey)
                    .addValue("status", STATUS_COMPLETED)
                    .addValue("resultType", resultType.name)
                    .addValue("resultJson", objectMapper.writeValueAsString(result))
                    .addValue("completedAt", Timestamp.from(Instant.now()))
                    .addValue("claimedBy", claimToken),
            )
        if (updated == 0) logFencingLost(idempotencyKey, "markCompleted", claimToken)
    }

    /**
     * claim_timeoutを超えて別リクエストに再クレームされた行は、元の所有者が確定してはならない
     * （ADR-0025決定3と同型のフェンシング前提、Issue #50、ADR-0027）。
     * `promptengine.infrastructure.messaging.OutboxRelayer.logFencingLost`と同じログ様式
     * （`*_fencing_lost`キー、構造化key=value引数）に合わせる。
     */
    private fun logFencingLost(
        idempotencyKey: String,
        phase: String,
        claimToken: String,
    ) {
        logger.warn(
            "idempotency_fencing_lost idempotencyKey={} phase={} claimToken={}",
            idempotencyKey,
            phase,
            claimToken,
        )
    }

    private fun findRow(idempotencyKey: String): IdempotencyKeyRow? =
        jdbcTemplate
            .query(
                """
                SELECT request_fingerprint, status, result_json, claimed_at, claimed_by
                FROM idempotency_keys
                WHERE idempotency_key = :idempotencyKey
                """.trimIndent(),
                MapSqlParameterSource().addValue("idempotencyKey", idempotencyKey),
            ) { rs, _ ->
                IdempotencyKeyRow(
                    requestFingerprint = rs.getString("request_fingerprint"),
                    status = rs.getString("status"),
                    resultJson = rs.getString("result_json"),
                    claimedAt = rs.getTimestamp("claimed_at")?.toInstant(),
                    claimedBy = rs.getString("claimed_by"),
                )
            }.firstOrNull()

    private data class IdempotencyKeyRow(
        val requestFingerprint: String,
        val status: String,
        val resultJson: String?,
        val claimedAt: Instant?,
        val claimedBy: String?,
    )

    companion object {
        private val logger = LoggerFactory.getLogger(JdbcIdempotentCommandExecutor::class.java)

        /** [claimTimeoutSeconds]の既定値。`prompt-engine-bootstrap`の`IdempotencyClaimProperties`既定値と揃える。 */
        private const val DEFAULT_CLAIM_TIMEOUT_SECONDS = 120L
    }
}
