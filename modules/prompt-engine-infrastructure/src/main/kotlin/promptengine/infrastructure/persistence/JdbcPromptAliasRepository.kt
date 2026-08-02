package promptengine.infrastructure.persistence

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import promptengine.domain.prompt.PromptAlias
import promptengine.domain.prompt.PromptAliasRepository
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.PromptVersionNotFoundException
import promptengine.domain.shared.SemVer
import java.util.UUID

/**
 * [PromptAliasRepository]のJDBC実装（`prompt_aliases`テーブル、設計書§12）。
 *
 * `VersionRef.Alias`解決（`LoadStage`が使用）を支える。P8時点では
 * `PromptRepository`にAlias解決の経路が無く未実装だった欠落を解消する
 * （実装ガイド§6.9で要求される全12ステージの実装のうち、Stage 1の`VersionRef`3種
 * 全対応が本来P8完了の条件だったため、P8のバグ修正として本クラスを追加する）。
 *
 * `(prompt_id, alias)`の一意性は`V7__prompt_aliases_unique_constraint.sql`で
 * 追加した一意制約が保証する（V1時点では制約が無く、同一alias名の複数行が
 * 作成できてしまっていた）。
 */
class JdbcPromptAliasRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
) : PromptAliasRepository {
    /** [promptKey]の[alias]に対応する[PromptAlias]を返す。存在しなければ`null`。 */
    override fun find(
        promptKey: PromptKey,
        alias: String,
    ): PromptAlias? =
        jdbcTemplate
            .query(
                """
                SELECT pv.version
                FROM prompt_aliases pa
                JOIN prompts p ON p.prompt_id = pa.prompt_id
                JOIN prompt_versions pv ON pv.version_id = pa.version_id
                WHERE p.prompt_key = :promptKey AND pa.alias = :alias
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("promptKey", promptKey.value)
                    .addValue("alias", alias),
            ) { rs, _ -> rs.getString("version") }
            .singleOrNull()
            ?.let { PromptAlias(promptKey, alias, parseSemVer(it)) }

    /** [promptKey]に設定された全[PromptAlias]をalias名の昇順で返す。1件も無ければ空リスト。 */
    override fun findAll(promptKey: PromptKey): List<PromptAlias> =
        jdbcTemplate.query(
            """
            SELECT pa.alias, pv.version
            FROM prompt_aliases pa
            JOIN prompts p ON p.prompt_id = pa.prompt_id
            JOIN prompt_versions pv ON pv.version_id = pa.version_id
            WHERE p.prompt_key = :promptKey
            ORDER BY pa.alias
            """.trimIndent(),
            MapSqlParameterSource().addValue("promptKey", promptKey.value),
        ) { rs, _ ->
            PromptAlias(promptKey, rs.getString("alias"), parseSemVer(rs.getString("version")))
        }

    /**
     * [alias]を作成または更新する（`(promptKey, alias)`が既存なら参照先Versionを更新する、
     * `(prompt_id, alias)`の一意制約とのON CONFLICTで判定）。
     *
     * @throws PromptVersionNotFoundException [alias].promptKeyのPromptが存在しない場合、
     *   または[alias].semVerに対応するVersionが存在しない場合
     */
    override fun upsert(alias: PromptAlias) {
        val promptId = findPromptId(alias.promptKey)
        val versionId = findVersionId(promptId, alias.semVer)
        jdbcTemplate.update(
            """
            INSERT INTO prompt_aliases (alias_id, prompt_id, alias, version_id)
            VALUES (:aliasId, :promptId, :alias, :versionId)
            ON CONFLICT (prompt_id, alias) DO UPDATE SET version_id = :versionId
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("aliasId", UUID.randomUUID())
                .addValue("promptId", promptId)
                .addValue("alias", alias.alias)
                .addValue("versionId", versionId),
        )
    }

    private fun findPromptId(key: PromptKey): UUID =
        jdbcTemplate
            .query(
                "SELECT prompt_id FROM prompts WHERE prompt_key = :promptKey",
                MapSqlParameterSource().addValue("promptKey", key.value),
            ) { rs, _ -> rs.getObject("prompt_id", UUID::class.java) }
            .singleOrNull() ?: throw PromptVersionNotFoundException.forKey(key)

    private fun findVersionId(
        promptId: UUID,
        semVer: SemVer,
    ): UUID =
        jdbcTemplate
            .query(
                "SELECT version_id FROM prompt_versions WHERE prompt_id = :promptId AND version = :version",
                MapSqlParameterSource()
                    .addValue("promptId", promptId)
                    .addValue("version", semVer.toString()),
            ) { rs, _ -> rs.getObject("version_id", UUID::class.java) }
            .singleOrNull() ?: throw PromptVersionNotFoundException(semVer)
}
