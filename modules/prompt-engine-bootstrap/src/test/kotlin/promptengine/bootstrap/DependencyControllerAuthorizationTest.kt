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
import promptengine.application.query.DependenciesHandler
import promptengine.bootstrap.config.SecurityConfig
import promptengine.interfaces.rest.DependencyController

/** `DependencyController`の認可テスト（Issue #115、設計書§13.1）。パターンは[AliasControllerAuthorizationTest]参照。 */
@WebMvcTest(DependencyController::class)
@Import(SecurityConfig::class, DependencyControllerAuthorizationTest.Stubs::class)
class DependencyControllerAuthorizationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    // ---- GET /prompts/{namespace}/{name}/dependencies?direction=（prompt:read） ----

    @Test
    fun `list はprompt-readスコープありなら401 403以外`() {
        val result =
            mockMvc.get("/api/v1/prompts/team/greeting/dependencies") {
                param("direction", "out")
                with(jwtWithScope("prompt:read"))
            }.andReturn()
        result.response.status shouldNotBeIn listOf(401, 403)
    }

    @Test
    fun `list はスコープ無しなら403`() {
        mockMvc.get("/api/v1/prompts/team/greeting/dependencies") {
            param("direction", "out")
            with(jwtWithScope("prompt:write"))
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `list はトークン無しなら401`() {
        mockMvc.get("/api/v1/prompts/team/greeting/dependencies") {
            param("direction", "out")
        }.andExpect { status { isUnauthorized() } }
    }

    /** [DependencyController]が要求する1 Handlerを`mockk(relaxed = true)`で差し替える（DB/Testcontainers不要）。 */
    @TestConfiguration
    class Stubs {
        @Bean fun dependenciesHandler(): DependenciesHandler = mockk(relaxed = true)
    }
}
