package promptengine.infrastructure.adapter.secret

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.shared.SensitiveValue

class EnvironmentSecretManagerAdapterTest {
    @Test
    fun `環境変数に対応する値があればSensitiveValueでラップして返す`() {
        val adapter = EnvironmentSecretManagerAdapter(environment = mapOf("PE_SECRET_APIKEYREF" to "sk-live-12345"))

        adapter.getSecret("apiKeyRef") shouldBe SensitiveValue.of("sk-live-12345")
    }

    @Test
    fun `対応する環境変数がなければnullを返す`() {
        val adapter = EnvironmentSecretManagerAdapter(environment = emptyMap())

        adapter.getSecret("apiKeyRef") shouldBe null
    }

    @Test
    fun `envVarPrefixを差し替えられる`() {
        val adapter =
            EnvironmentSecretManagerAdapter(
                environment = mapOf("CUSTOM_APIKEYREF" to "sk-live-12345"),
                envVarPrefix = "CUSTOM_",
            )

        adapter.getSecret("apiKeyRef") shouldBe SensitiveValue.of("sk-live-12345")
    }

    @Test
    fun `getSecretが返すSensitiveValueのtoStringはマスクされ実値を含まない`() {
        val adapter = EnvironmentSecretManagerAdapter(environment = mapOf("PE_SECRET_APIKEYREF" to "sk-live-12345"))

        val secret = adapter.getSecret("apiKeyRef")

        secret?.toString() shouldBe "***"
    }
}
