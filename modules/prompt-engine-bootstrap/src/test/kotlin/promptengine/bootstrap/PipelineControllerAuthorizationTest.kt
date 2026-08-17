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
import promptengine.application.pipeline.CompileUseCase
import promptengine.application.pipeline.ExecuteUseCase
import promptengine.application.pipeline.PipelineRequestFactory
import promptengine.application.pipeline.RenderUseCase
import promptengine.bootstrap.config.SecurityConfig
import promptengine.interfaces.rest.PipelineController

/** `PipelineController`の認可テスト（Issue #115、設計書§13.1・§13.2）。パターンは[AliasControllerAuthorizationTest]参照。 */
@WebMvcTest(PipelineController::class)
@Import(SecurityConfig::class, PipelineControllerAuthorizationTest.Stubs::class)
class PipelineControllerAuthorizationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    private val renderBody = """{"modelProfile":"gpt-class-large"}"""
    private val executeBody = """{"modelProfile":"gpt-class-large","executionPolicy":{"timeoutMs":30000}}"""

    // ---- POST /prompts/{namespace}/{name}/compile（prompt:read） ----

    @Test
    fun `compile はprompt-readスコープありなら401 403以外`() {
        val result =
            mockMvc.post("/api/v1/prompts/team/greeting/compile") {
                with(jwtWithScope("prompt:read"))
                contentType = MediaType.APPLICATION_JSON
                content = renderBody
            }.andReturn()
        result.response.status shouldNotBeIn listOf(401, 403)
    }

    @Test
    fun `compile はスコープ無しなら403`() {
        mockMvc.post("/api/v1/prompts/team/greeting/compile") {
            with(jwtWithScope("prompt:write"))
            contentType = MediaType.APPLICATION_JSON
            content = renderBody
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `compile はトークン無しなら401`() {
        mockMvc.post("/api/v1/prompts/team/greeting/compile") {
            contentType = MediaType.APPLICATION_JSON
            content = renderBody
        }.andExpect { status { isUnauthorized() } }
    }

    // ---- POST /prompts/{namespace}/{name}/render（prompt:read） ----

    @Test
    fun `render はprompt-readスコープありなら401 403以外`() {
        val result =
            mockMvc.post("/api/v1/prompts/team/greeting/render") {
                with(jwtWithScope("prompt:read"))
                contentType = MediaType.APPLICATION_JSON
                content = renderBody
            }.andReturn()
        result.response.status shouldNotBeIn listOf(401, 403)
    }

    @Test
    fun `render はスコープ無しなら403`() {
        mockMvc.post("/api/v1/prompts/team/greeting/render") {
            with(jwtWithScope("prompt:write"))
            contentType = MediaType.APPLICATION_JSON
            content = renderBody
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `render はトークン無しなら401`() {
        mockMvc.post("/api/v1/prompts/team/greeting/render") {
            contentType = MediaType.APPLICATION_JSON
            content = renderBody
        }.andExpect { status { isUnauthorized() } }
    }

    // ---- POST /prompts/{namespace}/{name}/execute（prompt:execute） ----

    @Test
    fun `execute はprompt-executeスコープありなら401 403以外`() {
        val result =
            mockMvc.post("/api/v1/prompts/team/greeting/execute") {
                with(jwtWithScope("prompt:execute"))
                contentType = MediaType.APPLICATION_JSON
                content = executeBody
            }.andReturn()
        result.response.status shouldNotBeIn listOf(401, 403)
    }

    @Test
    fun `execute はスコープ無しなら403`() {
        mockMvc.post("/api/v1/prompts/team/greeting/execute") {
            with(jwtWithScope("prompt:read"))
            contentType = MediaType.APPLICATION_JSON
            content = executeBody
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `execute はトークン無しなら401`() {
        mockMvc.post("/api/v1/prompts/team/greeting/execute") {
            contentType = MediaType.APPLICATION_JSON
            content = executeBody
        }.andExpect { status { isUnauthorized() } }
    }

    /** [PipelineController]が要求する4 Handlerを`mockk(relaxed = true)`で差し替える（DB/Testcontainers不要）。 */
    @TestConfiguration
    class Stubs {
        @Bean fun pipelineRequestFactory(): PipelineRequestFactory = mockk(relaxed = true)

        @Bean fun compileUseCase(): CompileUseCase = mockk(relaxed = true)

        @Bean fun renderUseCase(): RenderUseCase = mockk(relaxed = true)

        @Bean fun executeUseCase(): ExecuteUseCase = mockk(relaxed = true)
    }
}
