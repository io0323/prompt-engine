package promptengine.bootstrap.config

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment

/**
 * [ExecutionConfig.executionAdapter]のprovider切り替え・fail-fastロジックを、Spring Contextを
 * 起動せずBeanメソッドを直接呼び出して検証する（ADR-0030決定4、ADR-0031）。
 *
 * `production`プロファイルでBean生成が失敗しコンテキスト全体が起動しないことの実地検証は
 * [ProductionProfileGuardTest][promptengine.bootstrap.ProductionProfileGuardTest]
 * （Testcontainers、実際のSpring Boot起動シーケンス）が担う。本テストはそれを置き換えるものではなく、
 * より高速・詳細な単体検証を補う。
 *
 * `provider`の具体値は文字列リテラルとして書かず[ExecutionConfig.APAP_PROVIDER]を参照する。
 */
class ExecutionConfigTest {
    private val config = ExecutionConfig()

    @Test
    fun `provider=fakeは非productionプロファイルで問題なく構築される`() {
        shouldNotThrowAny {
            config.executionAdapter(
                environment = environmentWithProfiles(),
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
                    providerProperties = ExecutionProviderProperties(provider = FAKE_PROVIDER),
                )
            }
        e.message shouldContain "must not be selected under the 'production' profile"
    }

    @Test
    fun `provider=apapはAPAP未実装のため構築時にfail-fastする`() {
        val e =
            shouldThrow<IllegalStateException> {
                config.executionAdapter(
                    environment = environmentWithProfiles(),
                    providerProperties = ExecutionProviderProperties(provider = ExecutionConfig.APAP_PROVIDER),
                )
            }
        e.message shouldContain
            "promptengine.execution.provider=${ExecutionConfig.APAP_PROVIDER} is not yet implemented"
    }

    @Test
    fun `未知のproviderは構築時にfail-fastする`() {
        val e =
            shouldThrow<IllegalStateException> {
                config.executionAdapter(
                    environment = environmentWithProfiles(),
                    providerProperties = ExecutionProviderProperties(provider = "unknown-provider"),
                )
            }
        e.message shouldContain "Unknown promptengine.execution.provider"
    }

    private fun environmentWithProfiles(vararg profiles: String): MockEnvironment =
        MockEnvironment().apply { setActiveProfiles(*profiles) }

    companion object {
        private const val FAKE_PROVIDER = "fake"
    }
}
