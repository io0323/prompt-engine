package promptengine.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import promptengine.domain.governance.ApprovalRecord
import promptengine.domain.governance.ReviewCase
import promptengine.domain.governance.ReviewCaseDomainEvent
import promptengine.domain.governance.ReviewCaseMemento
import promptengine.domain.governance.ReviewCaseRepository
import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.PersistenceApi
import promptengine.domain.shared.SemVer
import java.sql.Timestamp
import java.util.UUID

/**
 * [ReviewCaseRepository] のJDBC実装（設計書§12 `review_cases`/`approvals`、ADR-0032）。
 *
 * `review_id`（`ReviewCase.reviewId`）はDomain側が[UUID.randomUUID]で採番する
 * （`ReviewCase.create`参照）ため、`prompts.prompt_id`のようなDB採番からdomain値への
 * 変換を要しない。そのため`review_cases`は単純なUPSERT（`ON CONFLICT (review_id)`）で
 * 保存できる。`review_cases`には`row_version`列（設計書§12）が無く、`approve`/`reject`は
 * 同一トランザクション内で行った`findInReview`の読み取り結果を前提に更新するため、
 * `SELECT ... FOR UPDATE`で対象行をロックしてから読む（`appendGovernanceDomainEvents`と
 * 同じ考え方だが、こちらは読み取り時点でロックする点が異なる。同時に複数の承認/却下が
 * 競合しても、片方が完了するまでもう片方はブロックされ、古い`approvals`の上に
 * 書き込んでしまうことはない）。
 *
 * `approvals`は追記専用に見えるが、実装を単純にするため保存のたびに
 * DELETE→再INSERTする（`variable_defs`と同じ方式、P2/ADR-0008）。件数は
 * レビュー参加者数程度で小さく、性能上の懸念はない。
 */
class JdbcReviewCaseRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
    private val objectMapper: ObjectMapper,
) : ReviewCaseRepository {
    @OptIn(PersistenceApi::class)
    override fun findInReview(
        promptKey: PromptKey,
        semVer: SemVer,
    ): ReviewCase? =
        transactionTemplate.execute {
            val row = findInReviewRowForUpdate(promptKey, semVer) ?: return@execute null
            val approvals = loadApprovals(row.reviewId)
            ReviewCase.restore(
                ReviewCaseMemento(
                    reviewId = row.reviewId,
                    promptKey = promptKey,
                    semVer = semVer,
                    submittedBy = row.submittedBy,
                    requiredApprovals = row.requiredApprovals,
                    status = reviewCaseStatusFromDbValue(row.status),
                    approvals = approvals,
                ),
            )
        }

    @OptIn(PersistenceApi::class)
    override fun save(
        reviewCase: ReviewCase,
        events: List<ReviewCaseDomainEvent>,
    ): ReviewCase =
        transactionTemplate.execute {
            upsertReviewCase(reviewCase)
            replaceApprovals(reviewCase.reviewId, reviewCase.approvals)
            jdbcTemplate.appendGovernanceDomainEvents(objectMapper, reviewCase.reviewId, events)
            reviewCase
        } ?: error("save transaction returned null")

    /**
     * `submitted_by`（[ReviewCase.submittedBy]、4-eyes判定の基準）は`review_cases`に
     * 列を持たない（設計書§12のER図に無いため列を追加しない、ADR-0032）。代わりに
     * 最初の`domain_events`行（`PromptReviewRequested`の`actor`）から復元する
     * （[findSubmittedBy]、[EventStorePromptRepository.findByKey]と同じ「RowMapper内で
     * 追加クエリを発行しない」流儀に従い、行を確定させてから別クエリで補う）。
     */
    private fun findInReviewRowForUpdate(
        promptKey: PromptKey,
        semVer: SemVer,
    ): ReviewCaseRow? {
        val row =
            jdbcTemplate.query(
                """
                SELECT rc.review_id, rc.status, rc.required_approvals
                FROM review_cases rc
                JOIN prompt_versions pv ON pv.version_id = rc.version_id
                JOIN prompts p ON p.prompt_id = pv.prompt_id
                WHERE p.prompt_key = :promptKey AND pv.version = :semVer AND rc.status = 'InReview'
                FOR UPDATE OF rc
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("promptKey", promptKey.value)
                    .addValue("semVer", semVer.toString()),
            ) { rs, _ ->
                Triple(
                    rs.getObject("review_id", UUID::class.java),
                    rs.getString("status"),
                    rs.getInt("required_approvals"),
                )
            }.firstOrNull() ?: return null

        val (reviewId, status, requiredApprovals) = row
        return ReviewCaseRow(reviewId, status, requiredApprovals, findSubmittedBy(reviewId))
    }

    private fun findSubmittedBy(reviewId: UUID): String =
        jdbcTemplate.queryForObject(
            """
            SELECT actor FROM domain_events
            WHERE aggregate_id = :reviewId AND event_type = 'PromptReviewRequested'
            ORDER BY sequence LIMIT 1
            """.trimIndent(),
            MapSqlParameterSource("reviewId", reviewId),
            String::class.java,
        ) ?: error("PromptReviewRequested event not found for review '$reviewId'")

    private fun resolveVersionId(
        promptKey: PromptKey,
        semVer: SemVer,
    ): UUID =
        jdbcTemplate.queryForObject(
            """
            SELECT pv.version_id FROM prompt_versions pv
            JOIN prompts p ON p.prompt_id = pv.prompt_id
            WHERE p.prompt_key = :promptKey AND pv.version = :semVer
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("promptKey", promptKey.value)
                .addValue("semVer", semVer.toString()),
            UUID::class.java,
        ) ?: error("PromptVersion not found for '${promptKey.value}' version '$semVer'")

    private fun upsertReviewCase(reviewCase: ReviewCase) {
        val versionId = resolveVersionId(reviewCase.promptKey, reviewCase.semVer)
        jdbcTemplate.update(
            """
            INSERT INTO review_cases (review_id, version_id, status, required_approvals)
            VALUES (:reviewId, :versionId, :status, :requiredApprovals)
            ON CONFLICT (review_id) DO UPDATE SET status = EXCLUDED.status
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("reviewId", reviewCase.reviewId)
                .addValue("versionId", versionId)
                .addValue("status", reviewCase.status.toDbValue())
                .addValue("requiredApprovals", reviewCase.requiredApprovals),
        )
    }

    private fun loadApprovals(reviewId: UUID): List<ApprovalRecord> =
        jdbcTemplate.query(
            """
            SELECT approver, decision, comment, decided_at
            FROM approvals WHERE review_id = :reviewId ORDER BY decided_at, approval_id
            """.trimIndent(),
            MapSqlParameterSource("reviewId", reviewId),
        ) { rs, _ ->
            ApprovalRecord(
                approver = rs.getString("approver"),
                decision = approvalDecisionFromDbValue(rs.getString("decision")),
                comment = rs.getString("comment"),
                decidedAt = rs.getTimestamp("decided_at").toInstant(),
            )
        }

    private fun replaceApprovals(
        reviewId: UUID,
        approvals: List<ApprovalRecord>,
    ) {
        jdbcTemplate.update(
            "DELETE FROM approvals WHERE review_id = :reviewId",
            MapSqlParameterSource("reviewId", reviewId),
        )
        if (approvals.isEmpty()) return

        val batchParams =
            approvals.map { approval ->
                MapSqlParameterSource()
                    .addValue("approvalId", UUID.randomUUID())
                    .addValue("reviewId", reviewId)
                    .addValue("approver", approval.approver)
                    .addValue("decision", approval.decision.toDbValue())
                    .addValue("comment", approval.comment)
                    .addValue("decidedAt", Timestamp.from(approval.decidedAt))
            }.toTypedArray()
        jdbcTemplate.batchUpdate(
            """
            INSERT INTO approvals (approval_id, review_id, approver, decision, comment, decided_at)
            VALUES (:approvalId, :reviewId, :approver, :decision, :comment, :decidedAt)
            """.trimIndent(),
            batchParams,
        )
    }

    private data class ReviewCaseRow(
        val reviewId: UUID,
        val status: String,
        val requiredApprovals: Int,
        val submittedBy: String,
    )
}
