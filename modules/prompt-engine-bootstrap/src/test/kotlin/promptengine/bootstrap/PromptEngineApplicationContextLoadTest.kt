package promptengine.bootstrap

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * P9cの最重要要件: Springコンテキストが実際に起動することを検証する（P1〜P8まで
 * Bootstrap未着手のため一度も起動確認されていなかった）。
 *
 * 全モジュールのDI配線（`PluginEngineConfig`・`PipelineEngineConfig`・`ExecutionConfig`・
 * `TransactionConfig`・`RepositoryConfig`・`AuditEventConfig`・`PipelineConfig`・
 * `CommandHandlersConfig`・`QueryHandlersConfig`・`SecurityConfig`）とFlywayマイグレーションが
 * 実際に実行できることをTestcontainers(PostgreSQL 16)で検証する。テスト自体は`contextLoads`の
 * 空実装のみだが、`@SpringBootTest`が起動時にコンテキストrefreshへ失敗すればテスト自体が
 * 失敗する（Spring Test標準の挙動）。
 *
 * アクティブプロファイルの組み合わせで3ケースを検証する:
 * - [NoActiveProfile]: 指定なし（既定の起動経路）。`ExecutionConfig.executionAdapter`が
 *   `@Value("#{environment.activeProfiles}")`を使っていた際、この経路でのみ`activeProfiles`が
 *   `null`に解決され`NullPointerException`で起動失敗する不具合があった（9c、初回起動確認で発覚）。
 *   この経路で確実に起動できることを固定する回帰テスト。
 * - [DevProfile]: `production`以外の任意の非空プロファイル（例: `dev`）でも同様に起動できることを
 *   確認する。`activeProfiles`配列が空でない場合の経路も併せて固定する。
 * - `production`プロファイルは意図的に起動が失敗するケース（[FakeExecutionAdapter]のガード、
 *   ADR-0015方針）であるため、ここでの「起動できる」検証には含めない。[ProductionProfileGuardTest]
 *   が専用に検証する。
 */
@Testcontainers
class PromptEngineApplicationContextLoadTest {
    @Nested
    @SpringBootTest
    inner class NoActiveProfile {
        @Test
        fun contextLoads() {
            // 起動できることの検証がこのテストの目的そのもの。
        }
    }

    @Nested
    @SpringBootTest
    @ActiveProfiles("dev")
    inner class DevProfile {
        @Test
        fun contextLoads() {
            // 起動できることの検証がこのテストの目的そのもの。
        }
    }

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")

        @DynamicPropertySource
        @JvmStatic
        fun registerDataSourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            // このコンテナはロール分離（Issue #85、ADR-0036）を再現しない単一ロールの
            // Testcontainersインスタンスのため、application.ymlのspring.flyway.user/password
            // 既定値（prompt_engine_migrator）を明示的に上書きし、Flywayもこのコンテナの
            // 唯一のロールで接続させる。
            registry.add("spring.flyway.url", postgres::getJdbcUrl)
            registry.add("spring.flyway.user", postgres::getUsername)
            registry.add("spring.flyway.password", postgres::getPassword)
        }
    }
}
