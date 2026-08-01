package promptengine.tests.integration.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import javax.sql.DataSource

/**
 * `V7__prompt_aliases_unique_constraint.sql`が、制約追加前に既存の重複
 * `(prompt_id, alias)`行を解消することを検証する統合テスト（CodeRabbitレビュー指摘）。
 *
 * V1〜V6までを先に適用した状態で意図的に重複行を作り、その後V7を適用しても
 * マイグレーション自体が失敗せず、重複が1行に統合されることを確認する。
 */
@Testcontainers
class V7PromptAliasesMigrationIntegrationTest {
    @Test
    fun `V7適用前に重複するprompt_aliases行があってもマイグレーションは成功し重複が1行に統合される`() {
        val dataSource: DataSource =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = postgres.jdbcUrl
                    username = postgres.username
                    password = postgres.password
                    driverClassName = postgres.driverClassName
                },
            )
        try {
            // V1〜V6までを適用（V7未適用のため一意制約はまだ存在しない）。
            Flyway.configure().dataSource(dataSource).target(MigrationVersion.fromVersion("6")).load().migrate()

            val jdbcTemplate = NamedParameterJdbcTemplate(dataSource)
            val promptId = UUID.randomUUID()
            jdbcTemplate.update(
                """
                INSERT INTO prompts (prompt_id, prompt_key, name, state, row_version, created_by, created_at, updated_at)
                VALUES (:promptId, :promptKey, :name, 'Published', 0, 'test', now(), now())
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("promptId", promptId)
                    .addValue("promptKey", "integration-test/v7-dedup")
                    .addValue("name", "v7-dedup"),
            )
            val versionId = UUID.randomUUID()
            jdbcTemplate.update(
                """
                INSERT INTO prompt_versions
                    (version_id, prompt_id, version, content, content_hash, status, context_requirements, created_by, created_at)
                VALUES (:versionId, :promptId, '1.0.0', 'body', 'hash', 'Published', '[]', 'test', now())
                """.trimIndent(),
                MapSqlParameterSource().addValue("versionId", versionId).addValue("promptId", promptId),
            )
            // V7適用前の状態を模して、同一(prompt_id, alias)の重複行を2件挿入する。
            repeat(2) {
                jdbcTemplate.update(
                    """
                    INSERT INTO prompt_aliases (alias_id, prompt_id, alias, version_id)
                    VALUES (:aliasId, :promptId, :alias, :versionId)
                    """.trimIndent(),
                    MapSqlParameterSource()
                        .addValue("aliasId", UUID.randomUUID())
                        .addValue("promptId", promptId)
                        .addValue("alias", "stable")
                        .addValue("versionId", versionId),
                )
            }
            val beforeCount =
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM prompt_aliases WHERE prompt_id = :promptId AND alias = :alias",
                    MapSqlParameterSource().addValue("promptId", promptId).addValue("alias", "stable"),
                    Int::class.java,
                )
            beforeCount shouldBe 2

            // V7を適用する。重複が残ったままだとADD CONSTRAINT UNIQUEが失敗するはずだが、
            // 事前のDELETEにより成功する。
            Flyway.configure().dataSource(dataSource).target(MigrationVersion.LATEST).load().migrate()

            val afterCount =
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM prompt_aliases WHERE prompt_id = :promptId AND alias = :alias",
                    MapSqlParameterSource().addValue("promptId", promptId).addValue("alias", "stable"),
                    Int::class.java,
                )
            afterCount shouldBe 1
        } finally {
            (dataSource as HikariDataSource).close()
        }
    }

    private companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")
    }
}
