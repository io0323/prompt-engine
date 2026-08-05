package promptengine.bootstrap

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * ADR-0015の方針（本番プロファイルでInMemory/Fake実装が選択された場合に起動時エラーとする）を
 * アプリケーション全体の起動シーケンスで検証する（P9c、9cキックオフの必須要件）。
 *
 * [FakeExecutionAdapter][promptengine.plugin.execution.fake.FakeExecutionAdapter]（唯一の
 * [promptengine.domain.execution.ExecutionAdapter]実装、M2で実APAPアダプタに置き換わるまでの
 * 暫定）が、`production`プロファイルの`init`ガード（P9cで追加）により`IllegalStateException`を
 * 投げ、Bean生成が失敗してコンテキスト全体が起動しないことを確認する。
 *
 * `@SpringBootTest`ではなく[SpringApplicationBuilder]を直接操作する: コンテキスト起動失敗を
 * 検証する場合、`@SpringBootTest`のテストライフサイクル自体が起動失敗時にテストを
 * `ApplicationContextException`で失敗させてしまい、`assertThrows`で捕まえられない
 * （Spring Test標準の挙動）ため。
 *
 * **9cのバグ修正で強化した検証**: 従来は`shouldThrow<Exception>`のみで、起動失敗の原因を
 * 一切問わなかった。このため`ExecutionConfig.executionAdapter`が
 * `@Value("#{environment.activeProfiles}")`で`NullPointerException`を投げていた不具合が
 * あった間も、本来検証したい[FakeExecutionAdapter]のガード自体は一度も通過していないのに
 * このテストは合格し続けていた（起動が失敗しさえすれば理由を問わず合格するため）。
 * 修正後は、原因チェーン（`cause`を辿った全体）の中に[FakeExecutionAdapter]のガードが
 * 投げる`IllegalStateException`（メッセージに[GUARD_MESSAGE_FRAGMENT]を含む）が実在することを
 * 明示的に確認する。Spring Bootの起動失敗時に最外層へ投げられる例外の具象型は
 * `UnsatisfiedDependencyException`/`BeanCreationException`等バージョン・配線経路により
 * 変動するため、最外層の型そのものへは依存しない。
 *
 * **同時に見つかった別の不具合**: データソース上書きは`SpringApplicationBuilder.properties(...)`
 * ではなく[SpringApplicationBuilder.run]へのコマンドライン引数（`--spring.datasource.url=...`）で
 * 行う。`properties(...)`は`SpringApplication`の`defaultProperties`として登録され、
 * `application.yml`の`${PE_DATASOURCE_URL:jdbc:postgresql://localhost:5432/prompt_engine}`が
 * 解決した実値より優先度が低い。強化前は無条件`shouldThrow<Exception>`だったため、
 * Testcontainersコンテナへ一度も接続できず`application.yml`既定のURL（ホストの別プロセスや
 * 存在しないDB）へ接続を試みて失敗した例外でも合格しており、この経路のバグも同時に
 * 隠蔽されていた。コマンドライン引数はSpring Bootのプロパティ優先順位で最上位のため確実に勝つ。
 */
@Testcontainers
class ProductionProfileGuardTest {
    @Test
    fun `productionプロファイルではFakeExecutionAdapterのガードにより起動が失敗する`() {
        val app =
            SpringApplicationBuilder(PromptEngineApplication::class.java)
                .web(WebApplicationType.NONE)
                .profiles("production")

        val thrown =
            shouldThrow<Exception> {
                app.run(
                    "--spring.datasource.url=${postgres.jdbcUrl}",
                    "--spring.datasource.username=${postgres.username}",
                    "--spring.datasource.password=${postgres.password}",
                ).close()
            }

        val guardCause =
            generateSequence<Throwable>(thrown) { it.cause }
                .filterIsInstance<IllegalStateException>()
                .firstOrNull { it.message?.contains(GUARD_MESSAGE_FRAGMENT) == true }

        withClue(
            "起動失敗の原因チェーンにFakeExecutionAdapterのガード" +
                "(IllegalStateException: '$GUARD_MESSAGE_FRAGMENT'を含む)が見つからなかった。" +
                "起動は別の理由で失敗している可能性がある: $thrown",
        ) {
            guardCause.shouldNotBeNull()
        }
    }

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")

        private const val GUARD_MESSAGE_FRAGMENT =
            "FakeExecutionAdapter must not be selected under the 'production' profile"
    }
}
