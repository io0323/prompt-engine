package promptengine.bootstrap

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Kubernetes liveness/readinessプローブ（P11、deploy/helm/prompt-engine）が実際に
 * 認証無しで到達可能であることを検証する（SecurityConfigの回帰テスト）。
 *
 * `/actuator/health`（完全一致）のみをpermitAllにしていた旧設定では
 * `/actuator/health/liveness`・`/actuator/health/readiness`（サブパス）が
 * `anyRequest().authenticated()`に落ちて401になり、kubeletからのプローブが
 * 全て失敗してPodがCrashLoopBackOffする不具合があった（P11で発見）。
 * `/actuator/health`配下すべて（Antパスの`**`ワイルドカード）へ広げた変更後、
 * サブパスも含めて到達可能であることと、
 * 保護対象のAPIは引き続き認証を要求すること（permitAllの範囲を広げすぎていないこと）を
 * 併せて固定する。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class ActuatorHealthProbeSecurityTest {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `actuator health のトップレベルは認証無しで到達できる`() {
        restTemplate.getForEntity("/actuator/health", String::class.java).statusCode shouldBe HttpStatus.OK
    }

    @Test
    fun `actuator health liveness は認証無しで到達できる`() {
        restTemplate.getForEntity("/actuator/health/liveness", String::class.java).statusCode shouldBe HttpStatus.OK
    }

    @Test
    fun `actuator health readiness は認証無しで到達できる`() {
        restTemplate.getForEntity("/actuator/health/readiness", String::class.java).statusCode shouldBe HttpStatus.OK
    }

    @Test
    fun `保護対象のAPIは引き続き認証を要求する`() {
        val response =
            restTemplate.postForEntity(
                "/api/v1/prompts/ns/name/render",
                null,
                String::class.java,
            )
        response.statusCode shouldBe HttpStatus.UNAUTHORIZED
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
        }
    }
}
