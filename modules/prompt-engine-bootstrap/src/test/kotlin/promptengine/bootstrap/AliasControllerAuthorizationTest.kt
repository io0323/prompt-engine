package promptengine.bootstrap

import io.kotest.matchers.collections.shouldNotBeIn
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import promptengine.application.command.SetAliasHandler
import promptengine.bootstrap.config.SecurityConfig
import promptengine.interfaces.rest.AliasController

/**
 * `AliasController`の認可テスト（Issue #115、設計書§13.1）。
 *
 * パターンは`ExperimentControllerAuthorizationTest`（PR #114）を踏襲する。
 * スコープ有りのケースはハンドラを`mockk(relaxed = true)`のまま明示的にスタブせず、
 * `@PreAuthorize`が403で弾いていないこと（401/403以外のステータスであること）だけを
 * 確認する（Issue #115の指示通り、認可以外の理由による4xxも許容する）。
 */
@WebMvcTest(AliasController::class)
@Import(SecurityConfig::class, AliasControllerAuthorizationTest.Stubs::class)
class AliasControllerAuthorizationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    private val setAliasBody = """{"alias":"stable","version":"1.0.0"}"""

    // ---- POST /prompts/{namespace}/{name}/aliases（prompt:publish） ----

    @Test
    fun `setAlias はprompt-publishスコープありなら401 403以外`() {
        val result =
            mockMvc.post("/api/v1/prompts/team/greeting/aliases") {
                with(jwtWithScope("prompt:publish"))
                contentType = MediaType.APPLICATION_JSON
                content = setAliasBody
            }.andReturn()
        result.response.status shouldNotBeIn listOf(401, 403)
    }

    @Test
    fun `setAlias はスコープ無しなら403`() {
        mockMvc.post("/api/v1/prompts/team/greeting/aliases") {
            with(jwtWithScope("prompt:read"))
            contentType = MediaType.APPLICATION_JSON
            content = setAliasBody
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `setAlias はトークン無しなら401`() {
        mockMvc.post("/api/v1/prompts/team/greeting/aliases") {
            contentType = MediaType.APPLICATION_JSON
            content = setAliasBody
        }.andExpect { status { isUnauthorized() } }
    }

    /** [AliasController]が要求する1 Handlerを`mockk(relaxed = true)`で差し替える（DB/Testcontainers不要）。 */
    @TestConfiguration
    class Stubs {
        @Bean fun setAliasHandler(): SetAliasHandler = mockk(relaxed = true)
    }
}
