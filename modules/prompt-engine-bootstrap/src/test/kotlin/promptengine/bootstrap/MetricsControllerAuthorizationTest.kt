package promptengine.bootstrap

import io.kotest.matchers.collections.shouldNotBeIn
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import promptengine.application.query.MetricsHandler
import promptengine.bootstrap.config.SecurityConfig
import promptengine.interfaces.rest.MetricsController

/**
 * `MetricsController`の認可テスト（Issue #115、設計書§13.1）。パターンは[AliasControllerAuthorizationTest]参照。
 *
 * `from`/`to`は`@RequestParam`必須（デフォルト無し）のため、`@PreAuthorize`到達前の
 * リクエストバインドで400になり403/401の検証にならないよう、全ケースで指定する。
 */
@WebMvcTest(MetricsController::class)
@Import(SecurityConfig::class, MetricsControllerAuthorizationTest.Stubs::class)
class MetricsControllerAuthorizationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    // ---- GET /metrics/prompts/{namespace}/{name}?from=&to=（prompt:read） ----

    @Test
    fun `summarize はprompt-readスコープありなら401 403以外`() {
        val result =
            mockMvc.get("/api/v1/metrics/prompts/team/greeting") {
                param("from", "2026-01-01T00:00:00Z")
                param("to", "2026-01-02T00:00:00Z")
                with(jwtWithScope("prompt:read"))
            }.andReturn()
        result.response.status shouldNotBeIn listOf(401, 403)
    }

    @Test
    fun `summarize はスコープ無しなら403`() {
        mockMvc.get("/api/v1/metrics/prompts/team/greeting") {
            param("from", "2026-01-01T00:00:00Z")
            param("to", "2026-01-02T00:00:00Z")
            with(jwtWithScope("prompt:write"))
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `summarize はトークン無しなら401`() {
        mockMvc.get("/api/v1/metrics/prompts/team/greeting") {
            param("from", "2026-01-01T00:00:00Z")
            param("to", "2026-01-02T00:00:00Z")
        }.andExpect { status { isUnauthorized() } }
    }

    /** [MetricsController]が要求する1 Handlerを`mockk(relaxed = true)`で差し替える（DB/Testcontainers不要）。 */
    @TestConfiguration
    class Stubs {
        @Bean fun metricsHandler(): MetricsHandler = mockk(relaxed = true)
    }
}
