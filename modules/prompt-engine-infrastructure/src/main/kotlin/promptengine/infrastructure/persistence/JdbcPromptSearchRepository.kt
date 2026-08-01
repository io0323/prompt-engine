package promptengine.infrastructure.persistence

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.PromptSearchCriteria
import promptengine.domain.prompt.PromptSearchRepository
import promptengine.domain.prompt.PromptSummary
import promptengine.domain.shared.Page
import java.util.UUID

/**
 * [PromptSearchRepository]のJDBC実装（`prompts`/`prompt_versions`/`categories`/`tags`/
 * `prompt_tags`テーブル、設計書§12、ADR-0017）。
 *
 * 各Promptの「代表Version」（[PromptSummary.status]等の元）はPublished優先、無ければ
 * `created_at`降順の最新Versionとする（`ROW_NUMBER() OVER (...)`で1件に絞る）。
 */
class JdbcPromptSearchRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
) : PromptSearchRepository {
    override fun search(criteria: PromptSearchCriteria): Page<PromptSummary> {
        val (whereClause, params) = buildWhereClause(criteria)
        val baseQuery = buildBaseQuery(whereClause)

        val countSql = "SELECT COUNT(DISTINCT p.prompt_id) $baseQuery"
        val total = jdbcTemplate.queryForObject(countSql, params, Long::class.java) ?: 0L

        params.addValue("limit", criteria.size).addValue("offset", criteria.page * criteria.size)
        val items = queryItems(baseQuery, params)
        return Page(items, criteria.page, criteria.size, total)
    }

    private fun buildWhereClause(criteria: PromptSearchCriteria): Pair<String, MapSqlParameterSource> {
        val conditions = mutableListOf<String>()
        val params = MapSqlParameterSource()
        if (criteria.q != null) {
            conditions += "(p.name ILIKE :q OR p.prompt_key ILIKE :q)"
            params.addValue("q", "%${criteria.q}%")
        }
        if (criteria.category != null) {
            conditions += "c.name = :category"
            params.addValue("category", criteria.category)
        }
        val status = criteria.status
        if (status != null) {
            conditions += "rv.status = :status"
            params.addValue("status", status.toDbValue())
        }
        if (criteria.tag != null) {
            conditions +=
                "EXISTS (SELECT 1 FROM prompt_tags pt2 JOIN tags t2 ON t2.tag_id = pt2.tag_id " +
                "WHERE pt2.prompt_id = p.prompt_id AND t2.name = :tag)"
            params.addValue("tag", criteria.tag)
        }
        val whereClause = if (conditions.isEmpty()) "" else "WHERE ${conditions.joinToString(" AND ")}"
        return whereClause to params
    }

    private fun buildBaseQuery(whereClause: String): String =
        """
        FROM prompts p
        LEFT JOIN categories c ON c.category_id = p.category_id
        JOIN (
            SELECT prompt_id, version, status,
                   ROW_NUMBER() OVER (
                       PARTITION BY prompt_id
                       ORDER BY CASE WHEN status = 'Published' THEN 0 ELSE 1 END, created_at DESC
                   ) AS rn
            FROM prompt_versions
        ) rv ON rv.prompt_id = p.prompt_id AND rv.rn = 1
        LEFT JOIN (
            SELECT prompt_id, version, status,
                   ROW_NUMBER() OVER (PARTITION BY prompt_id ORDER BY created_at DESC) AS rn
            FROM prompt_versions
        ) latest ON latest.prompt_id = p.prompt_id AND latest.rn = 1
        LEFT JOIN (
            SELECT prompt_id, version
            FROM prompt_versions WHERE status = 'Published'
        ) published ON published.prompt_id = p.prompt_id
        $whereClause
        """.trimIndent()

    private fun queryItems(
        baseQuery: String,
        params: MapSqlParameterSource,
    ): List<PromptSummary> =
        jdbcTemplate.query(
            """
            SELECT p.prompt_id, p.prompt_key, p.name, c.name AS category_name, rv.status,
                   latest.version AS latest_version, published.version AS published_version
            $baseQuery
            ORDER BY p.prompt_key
            LIMIT :limit OFFSET :offset
            """.trimIndent(),
            params,
        ) { rs, _ ->
            val promptId = rs.getObject("prompt_id", UUID::class.java)
            PromptSummary(
                key = PromptKey(rs.getString("prompt_key")),
                name = rs.getString("name"),
                category = rs.getString("category_name"),
                tags = findTags(promptId),
                status = lifecycleStateFromDbValue(rs.getString("status")),
                latestVersion = rs.getString("latest_version"),
                publishedVersion = rs.getString("published_version"),
            )
        }

    private fun findTags(promptId: UUID): List<String> =
        jdbcTemplate.query(
            """
            SELECT t.name FROM tags t
            JOIN prompt_tags pt ON pt.tag_id = t.tag_id
            WHERE pt.prompt_id = :promptId
            ORDER BY t.name
            """.trimIndent(),
            MapSqlParameterSource().addValue("promptId", promptId),
        ) { rs, _ -> rs.getString("name") }
}
