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
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import promptengine.application.command.ArchiveHandler
import promptengine.application.command.CreatePromptHandler
import promptengine.application.command.UpdatePromptMetadataHandler
import promptengine.application.query.GetPromptHandler
import promptengine.application.query.SearchPromptsHandler
import promptengine.bootstrap.config.SecurityConfig
import promptengine.interfaces.rest.PromptController

/** `PromptController`の認可テスト（Issue #115、設計書§13.1）。パターンは[AliasControllerAuthorizationTest]参照。 */
@WebMvcTest(PromptController::class)
@Import(SecurityConfig::class, PromptControllerAuthorizationTest.Stubs::class)
class PromptControllerAuthorizationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    private val createBody = """{"key":"team/greeting","name":"Greeting","semVer":"1.0.0","source":"s"}"""
    private val updateMetadataBody = """{"name":"Greeting"}"""

    // ---- POST /prompts（prompt:write） ----

    @Test
    fun `create はprompt-writeスコープありなら401 403以外`() {
        val result =
            mockMvc.post("/api/v1/prompts") {
                with(jwtWithScope("prompt:write"))
                contentType = MediaType.APPLICATION_JSON
                content = createBody
            }.andReturn()
        result.response.status shouldNotBeIn listOf(401, 403)
    }

    @Test
    fun `create はスコープ無しなら403`() {
        mockMvc.post("/api/v1/prompts") {
            with(jwtWithScope("prompt:read"))
            contentType = MediaType.APPLICATION_JSON
            content = createBody
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `create はトークン無しなら401`() {
        mockMvc.post("/api/v1/prompts") {
            contentType = MediaType.APPLICATION_JSON
            content = createBody
        }.andExpect { status { isUnauthorized() } }
    }

    // ---- GET /prompts（prompt:read） ----

    @Test
    fun `search はprompt-readスコープありなら401 403以外`() {
        val result =
            mockMvc.get("/api/v1/prompts") {
                with(jwtWithScope("prompt:read"))
            }.andReturn()
        result.response.status shouldNotBeIn listOf(401, 403)
    }

    @Test
    fun `search はスコープ無しなら403`() {
        mockMvc.get("/api/v1/prompts") {
            with(jwtWithScope("prompt:write"))
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `search はトークン無しなら401`() {
        mockMvc.get("/api/v1/prompts") {}
            .andExpect { status { isUnauthorized() } }
    }

    // ---- GET /prompts/{namespace}/{name}（prompt:read） ----

    @Test
    fun `get はprompt-readスコープありなら401 403以外`() {
        val result =
            mockMvc.get("/api/v1/prompts/team/greeting") {
                with(jwtWithScope("prompt:read"))
            }.andReturn()
        result.response.status shouldNotBeIn listOf(401, 403)
    }

    @Test
    fun `get はスコープ無しなら403`() {
        mockMvc.get("/api/v1/prompts/team/greeting") {
            with(jwtWithScope("prompt:write"))
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `get はトークン無しなら401`() {
        mockMvc.get("/api/v1/prompts/team/greeting") {}
            .andExpect { status { isUnauthorized() } }
    }

    // ---- PATCH /prompts/{namespace}/{name}（prompt:write） ----

    @Test
    fun `updateMetadata はprompt-writeスコープありなら401 403以外`() {
        val result =
            mockMvc.patch("/api/v1/prompts/team/greeting") {
                with(jwtWithScope("prompt:write"))
                contentType = MediaType.APPLICATION_JSON
                content = updateMetadataBody
            }.andReturn()
        result.response.status shouldNotBeIn listOf(401, 403)
    }

    @Test
    fun `updateMetadata はスコープ無しなら403`() {
        mockMvc.patch("/api/v1/prompts/team/greeting") {
            with(jwtWithScope("prompt:read"))
            contentType = MediaType.APPLICATION_JSON
            content = updateMetadataBody
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `updateMetadata はトークン無しなら401`() {
        mockMvc.patch("/api/v1/prompts/team/greeting") {
            contentType = MediaType.APPLICATION_JSON
            content = updateMetadataBody
        }.andExpect { status { isUnauthorized() } }
    }

    // ---- DELETE /prompts/{namespace}/{name}?version=（prompt:admin） ----

    @Test
    fun `archive はprompt-adminスコープありなら401 403以外`() {
        val result =
            mockMvc.delete("/api/v1/prompts/team/greeting") {
                param("version", "1.0.0")
                with(jwtWithScope("prompt:admin"))
            }.andReturn()
        result.response.status shouldNotBeIn listOf(401, 403)
    }

    @Test
    fun `archive はスコープ無しなら403`() {
        mockMvc.delete("/api/v1/prompts/team/greeting") {
            param("version", "1.0.0")
            with(jwtWithScope("prompt:write"))
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `archive はトークン無しなら401`() {
        mockMvc.delete("/api/v1/prompts/team/greeting") {
            param("version", "1.0.0")
        }.andExpect { status { isUnauthorized() } }
    }

    /** [PromptController]が要求する5 Handlerを`mockk(relaxed = true)`で差し替える（DB/Testcontainers不要）。 */
    @TestConfiguration
    class Stubs {
        @Bean fun createPromptHandler(): CreatePromptHandler = mockk(relaxed = true)

        @Bean fun updatePromptMetadataHandler(): UpdatePromptMetadataHandler = mockk(relaxed = true)

        @Bean fun archiveHandler(): ArchiveHandler = mockk(relaxed = true)

        @Bean fun getPromptHandler(): GetPromptHandler = mockk(relaxed = true)

        @Bean fun searchPromptsHandler(): SearchPromptsHandler = mockk(relaxed = true)
    }
}
