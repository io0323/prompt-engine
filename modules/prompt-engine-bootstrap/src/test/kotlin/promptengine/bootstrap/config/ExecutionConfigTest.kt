package promptengine.bootstrap.config

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import promptengine.infrastructure.adapter.secret.EnvironmentSecretManagerAdapter

/**
 * [ExecutionConfig.executionAdapter]のprovider切り替え・fail-fastロジックを、Spring Contextを
 * 起動せずBeanメソッドを直接呼び出して検証する（M2-1c、ADR-0030決定4）。
 *
 * `production`プロファイルでBean生成が失敗しコンテキスト全体が起動しないことの実地検証は
 * [ProductionProfileGuardTest][promptengine.bootstrap.ProductionProfileGuardTest]
 * （Testcontainers、実際のSpring Boot起動シーケンス）が担う。本テストはそれを置き換えるものではなく、
 * より高速・詳細な単体検証を補う。特に「実プロバイダかつAPIキー設定済みで実際にBean生成が
 * 成功する」経路は、[EnvironmentSecretManagerAdapter]が`System.getenv()`を直接読むため
 * （Spring `Environment`経由ではない）コマンドライン引数上書きでは到達できず、
 * [EnvironmentSecretManagerAdapter]のテスト用コンストラクタ（Mapを直接注入）を使う本テストが
 * 唯一の検証箇所となる。
 *
 * `provider`の具体値は文字列リテラルとして書かず[ExecutionConfig.REAL_PROVIDER]を参照する
 * （`ProviderNameContainmentTest`のallowlistを`ExecutionConfig.kt`自身に限定し続けるため）。
 */
class ExecutionConfigTest {
    private val config = ExecutionConfig()

    @Test
    fun `provider=fakeは非productionプロファイルで問題なく構築される`() {
        shouldNotThrowAny {
            config.executionAdapter(
                environment = environmentWithProfiles(),
                secretManagerAdapter = secretManagerAdapter(),
                providerProperties = ExecutionProviderProperties(provider = FAKE_PROVIDER),
            )
        }
    }

    @Test
    fun `provider=fakeはproductionプロファイルでFakeExecutionAdapter自身のガードにより構築が失敗する`() {
        val e =
            shouldThrow<IllegalStateException> {
                config.executionAdapter(
                    environment = environmentWithProfiles("production"),
                    secretManagerAdapter = secretManagerAdapter(),
                    providerProperties = ExecutionProviderProperties(provider = FAKE_PROVIDER),
                )
            }
        e.message shouldContain "must not be selected under the 'production' profile"
    }

    @Test
    fun `実プロバイダでAPIキー設定済みならプロファイルに関わらず構築できる`() {
        shouldNotThrowAny {
            config.executionAdapter(
                environment = environmentWithProfiles("production"),
                secretManagerAdapter = secretManagerAdapter(apiKey = "sk-test-dummy"),
                providerProperties = ExecutionProviderProperties(provider = ExecutionConfig.REAL_PROVIDER),
            )
        }
    }

    @Test
    fun `実プロバイダでAPIキー未設定なら構築時にfail-fastする`() {
        val e =
            shouldThrow<IllegalStateException> {
                config.executionAdapter(
                    environment = environmentWithProfiles(),
                    secretManagerAdapter = secretManagerAdapter(),
                    providerProperties = ExecutionProviderProperties(provider = ExecutionConfig.REAL_PROVIDER),
                )
            }
        e.message shouldContain "requires the '${ExecutionConfig.API_KEY_SECRET_NAME}' secret to be configured"
    }

    @Test
    fun `未知のproviderは構築時にfail-fastする`() {
        val e =
            shouldThrow<IllegalStateException> {
                config.executionAdapter(
                    environment = environmentWithProfiles(),
                    secretManagerAdapter = secretManagerAdapter(),
                    providerProperties = ExecutionProviderProperties(provider = "unknown-provider"),
                )
            }
        e.message shouldContain "Unknown promptengine.execution.provider"
    }

    private fun environmentWithProfiles(vararg profiles: String): MockEnvironment =
        MockEnvironment().apply { setActiveProfiles(*profiles) }

    private fun secretManagerAdapter(apiKey: String? = null): EnvironmentSecretManagerAdapter =
        EnvironmentSecretManagerAdapter(
            environment =
                apiKey?.let {
                    mapOf((EnvironmentSecretManagerAdapter.DEFAULT_PREFIX + ExecutionConfig.API_KEY_SECRET_NAME) to it)
                } ?: emptyMap(),
        )

    companion object {
        private const val FAKE_PROVIDER = "fake"
    }
}
