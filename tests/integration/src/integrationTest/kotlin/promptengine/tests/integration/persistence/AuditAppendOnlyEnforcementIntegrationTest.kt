package promptengine.tests.integration.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * `audit_logs`/`domain_events`のDB層追記専用強制（Issue #85、ADR-0036）を検証する。
 *
 * ユーザー指摘の核心: 「設定しただけで終わらせない」— GRANT/REVOKEを設定しても、それが
 * 実際にDB接続レベルで効いているかは、その権限で実際に操作を試して失敗することでしか
 * 確認できない。本テストはPostgreSQL 16実体に対し、`prompt_engine`（restricted、
 * アプリ実行時ロール）で接続してUPDATE/DELETEが実際に権限エラーで失敗することと、
 * INSERT/SELECT・他テーブルへの通常操作は成功することの両方を確認する
 * （権限を絞りすぎて監査が書けなくなる逆方向の失敗も防ぐ）。
 *
 * ロール構成は`compose.yaml`と同じ2ロールをこのテスト専用のコンテナ内に再現する
 * （`prompt_engine_migrator`をコンテナのブートストラップロールとする。アプリ実行時ロール
 * `prompt_engine`はFlyway migration V20が冪等に作成するため、テスト側で別途作成する必要は
 * ない）。Flyway自体も`prompt_engine_migrator`として実行し、`prompt_engine`ではDDL
 * （ALTER TABLE）が実行できないことも確認する。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditAppendOnlyEnforcementIntegrationTest {
    private lateinit var migratorDataSource: DataSource
    private lateinit var appDataSource: DataSource
    private lateinit var appJdbcTemplate: NamedParameterJdbcTemplate

    @BeforeAll
    fun setUp() {
        migratorDataSource = hikariDataSource(username = MIGRATOR_USER, password = MIGRATOR_PASSWORD)

        Flyway.configure().dataSource(migratorDataSource).load().migrate()

        appDataSource = hikariDataSource(username = APP_USER, password = APP_PASSWORD)
        appJdbcTemplate = NamedParameterJdbcTemplate(appDataSource)
    }

    @AfterAll
    fun tearDown() {
        (migratorDataSource as HikariDataSource).close()
        (appDataSource as HikariDataSource).close()
    }

    private fun hikariDataSource(
        username: String,
        password: String,
    ): DataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = postgres.jdbcUrl
                this.username = username
                this.password = password
                maximumPoolSize = 2
            },
        )

    @Test
    fun `prompt_engineロールはaudit_logsへINSERT_SELECTできるがUPDATE_DELETEは権限エラーで失敗する`() {
        val auditId = UUID.randomUUID()
        appJdbcTemplate.update(
            """
            INSERT INTO audit_logs (audit_id, aggregate_type, aggregate_id, action, actor, payload, trace_id, occurred_at)
            VALUES (:auditId, 'Prompt', :aggregateId, 'PromptCreated', 'tester', '{}'::json, 'trace-1', :occurredAt)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("auditId", auditId)
                .addValue("aggregateId", UUID.randomUUID())
                .addValue("occurredAt", java.sql.Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"))),
        )

        val selected =
            appJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE audit_id = :auditId",
                MapSqlParameterSource("auditId", auditId),
                Int::class.java,
            )
        selected shouldBe 1

        val updateException =
            shouldThrow<Exception> {
                appJdbcTemplate.update(
                    "UPDATE audit_logs SET actor = 'tampered' WHERE audit_id = :auditId",
                    MapSqlParameterSource("auditId", auditId),
                )
            }
        rootSqlException(updateException).sqlState shouldBe PERMISSION_DENIED_SQLSTATE

        val deleteException =
            shouldThrow<Exception> {
                appJdbcTemplate.update(
                    "DELETE FROM audit_logs WHERE audit_id = :auditId",
                    MapSqlParameterSource("auditId", auditId),
                )
            }
        rootSqlException(deleteException).sqlState shouldBe PERMISSION_DENIED_SQLSTATE
    }

    @Test
    fun `prompt_engineロールはdomain_eventsへINSERT_SELECTできるがUPDATE_DELETEは権限エラーで失敗する`() {
        val eventId = UUID.randomUUID()
        appJdbcTemplate.update(
            """
            INSERT INTO domain_events (event_id, aggregate_type, aggregate_id, sequence, event_type, actor, trace_id, payload, occurred_at)
            VALUES (:eventId, 'Prompt', :aggregateId, 1, 'PromptCreated', 'tester', 'trace-1', '{}'::json, :occurredAt)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("eventId", eventId)
                .addValue("aggregateId", UUID.randomUUID())
                .addValue("occurredAt", java.sql.Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"))),
        )

        val selected =
            appJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM domain_events WHERE event_id = :eventId",
                MapSqlParameterSource("eventId", eventId),
                Int::class.java,
            )
        selected shouldBe 1

        val updateException =
            shouldThrow<Exception> {
                appJdbcTemplate.update(
                    "UPDATE domain_events SET actor = 'tampered' WHERE event_id = :eventId",
                    MapSqlParameterSource("eventId", eventId),
                )
            }
        rootSqlException(updateException).sqlState shouldBe PERMISSION_DENIED_SQLSTATE

        val deleteException =
            shouldThrow<Exception> {
                appJdbcTemplate.update(
                    "DELETE FROM domain_events WHERE event_id = :eventId",
                    MapSqlParameterSource("eventId", eventId),
                )
            }
        rootSqlException(deleteException).sqlState shouldBe PERMISSION_DENIED_SQLSTATE
    }

    /**
     * 権限を絞りすぎて他機能まで壊れていないことの確認（逆方向の失敗防止）。
     * `prompts`テーブルはaudit_logs/domain_eventsと異なりUPDATE/DELETEも通常通り許可される。
     */
    @Test
    fun `prompt_engineロールは追記専用対象外のテーブル(prompts)へは通常通りCRUDできる`() {
        val promptId = UUID.randomUUID()
        val now = java.sql.Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"))
        appJdbcTemplate.update(
            """
            INSERT INTO prompts (prompt_id, prompt_key, name, state, created_by, created_at, updated_at)
            VALUES (:promptId, :promptKey, 'role-check', 'Draft', 'tester', :now, :now)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("promptId", promptId)
                .addValue("promptKey", "audit-it/role-check-$promptId")
                .addValue("now", now),
        )

        appJdbcTemplate.update(
            "UPDATE prompts SET description = 'updated' WHERE prompt_id = :promptId",
            MapSqlParameterSource("promptId", promptId),
        )
        appJdbcTemplate.update(
            "DELETE FROM prompts WHERE prompt_id = :promptId",
            MapSqlParameterSource("promptId", promptId),
        )

        val remaining =
            appJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM prompts WHERE prompt_id = :promptId",
                MapSqlParameterSource("promptId", promptId),
                Int::class.java,
            )
        remaining shouldBe 0
    }

    /**
     * Flywayがmigratorロールで実行され、アプリロールではDDLが実行できないことの確認。
     */
    @Test
    fun `prompt_engineロールはaudit_logsへALTER_TABLE等のDDLを実行できない`() {
        val ddlException =
            shouldThrow<Exception> {
                appJdbcTemplate.update(
                    "ALTER TABLE audit_logs ADD COLUMN role_check_column TEXT",
                    MapSqlParameterSource(),
                )
            }
        rootSqlException(ddlException).sqlState shouldBe PERMISSION_DENIED_SQLSTATE
    }

    private fun rootSqlException(throwable: Throwable): SQLException =
        generateSequence(throwable) { it.cause }.filterIsInstance<SQLException>().first()

    private companion object {
        const val MIGRATOR_USER = "prompt_engine_migrator"
        const val MIGRATOR_PASSWORD = "prompt_engine_migrator"
        const val APP_USER = "prompt_engine"
        const val APP_PASSWORD = "prompt_engine"

        /** PostgreSQLの`insufficient_privilege`SQLSTATE（GRANT/REVOKE違反は常にこのコード）。 */
        const val PERMISSION_DENIED_SQLSTATE = "42501"

        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16")
                .withUsername(MIGRATOR_USER)
                .withPassword(MIGRATOR_PASSWORD)
                .withDatabaseName("prompt_engine")
    }
}
