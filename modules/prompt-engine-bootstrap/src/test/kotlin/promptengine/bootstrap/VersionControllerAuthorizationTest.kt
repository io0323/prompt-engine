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
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import promptengine.application.command.CreateVersionHandler
import promptengine.application.command.DeprecateHandler
import promptengine.application.command.PublishHandler
import promptengine.application.command.RollbackHandler
import promptengine.application.query.DiffHandler
import promptengine.application.query.GetVersionHandler
import promptengine.bootstrap.config.SecurityConfig
import promptengine.interfaces.rest.VersionController

/** `VersionController`の認可テスト（Issue #115、設計書§13.1）。パターンは[AliasControllerAuthorizationTest]参照。 */
@WebMvcTest(VersionController::class)
@Import(SecurityConfig::class, VersionControllerAuthorizationTest.Stubs::class)
class VersionControllerAuthorizationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    private val createVersionBody = """{"semVer":"1.0.0","source":"s"}"""
    private val rollbackBody = """{"targetVersion":"1.0.0"}"""

    // ---- POST /prompts/{namespace}/{name}/versions（prompt:write） ----

    @Test
    fun `createVersion はprompt-writeスコープありなら401 403以外`() {
        val result =
            mockMvc.post("/api/v1/prompts/team/greeting/versions") {
                with(jwtWithScope("prompt:write"))
                contentType = MediaType.APPLICATION_JSON
                content = createVersionBody
            }.andReturn()
        result.response.status shouldNotBeIn listOf(401, 403)
    }

    @Test
    fun `createVersion はスコープ無しなら403`() {
        mockMvc.post("/api/v1/prompts/team/greeting/versions") {
            with(jwtWithScope("prompt:read"))
            contentType = MediaType.APPLICATION_JSON
            content = createVersionBody
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `createVersion はトークン無しなら401`() {
        mockMvc.post("/api/v1/prompts/team/greeting/versions") {
            contentType = MediaType.APPLICATION_JSON
            content = createVersionBody
        }.andExpect { status { isUnauthorized() } }
    }

    // ---- GET /prompts/{namespace}/{name}/versions/{version}（prompt:read） ----

    @Test
    fun `getVersion はprompt-readスコープありなら401 403以外`() {
        val result =
            mockMvc.get("/api/v1/prompts/team/greeting/versions/1.0.0") {
                with(jwtWithScope("prompt:read"))
            }.andReturn()
        result.response.status shouldNotBeIn listOf(401, 403)
    }

    @Test
    fun `getVersion はスコープ無しなら403`() {
        mockMvc.get("/api/v1/prompts/team/greeting/versions/1.0.0") {
            with(jwtWithScope("prompt:write"))
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `getVersion はトークン無しなら401`() {
        mockMvc.get("/api/v1/prompts/team/greeting/versions/1.0.0") {}
            .andExpect { status { isUnauthorized() } }
    }

    // ---- GET /prompts/{namespace}/{name}/diff?from=&to=（prompt:read） ----

    @Test
    fun `diff はprompt-readスコープありなら401 403以外`() {
        val result =
            mockMvc.get("/api/v1/prompts/team/greeting/diff") {
                param("from", "1.0.0")
                param("to", "1.1.0")
                with(jwtWithScope("prompt:read"))
            }.andReturn()
        result.response.status shouldNotBeIn listOf(401, 403)
    }

    @Test
    fun `diff はスコープ無しなら403`() {
        mockMvc.get("/api/v1/prompts/team/greeting/diff") {
            param("from", "1.0.0")
            param("to", "1.1.0")
            with(jwtWithScope("prompt:write"))
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `diff はトークン無しなら401`() {
        mockMvc.get("/api/v1/prompts/team/greeting/diff") {
            param("from", "1.0.0")
            param("to", "1.1.0")
        }.andExpect { status { isUnauthorized() } }
    }

    // ---- POST /prompts/{namespace}/{name}/versions/{version}/publish（prompt:publish） ----

    @Test
    fun `publish はprompt-publishスコープありなら401 403以外`() {
        val result =
            mockMvc.post("/api/v1/prompts/team/greeting/versions/1.0.0/publish") {
                with(jwtWithScope("prompt:publish"))
            }.andReturn()
        result.response.status shouldNotBeIn listOf(401, 403)
    }

    @Test
    fun `publish はスコープ無しなら403`() {
        mockMvc.post("/api/v1/prompts/team/greeting/versions/1.0.0/publish") {
            with(jwtWithScope("prompt:write"))
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `publish はトークン無しなら401`() {
        mockMvc.post("/api/v1/prompts/team/greeting/versions/1.0.0/publish") {}
            .andExpect { status { isUnauthorized() } }
    }

    // ---- POST /prompts/{namespace}/{name}/rollback（prompt:publish） ----

    @Test
    fun `rollback はprompt-publishスコープありなら401 403以外`() {
        val result =
            mockMvc.post("/api/v1/prompts/team/greeting/rollback") {
                with(jwtWithScope("prompt:publish"))
                contentType = MediaType.APPLICATION_JSON
                content = rollbackBody
            }.andReturn()
        result.response.status shouldNotBeIn listOf(401, 403)
    }

    @Test
    fun `rollback はスコープ無しなら403`() {
        mockMvc.post("/api/v1/prompts/team/greeting/rollback") {
            with(jwtWithScope("prompt:write"))
            contentType = MediaType.APPLICATION_JSON
            content = rollbackBody
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `rollback はトークン無しなら401`() {
        mockMvc.post("/api/v1/prompts/team/greeting/rollback") {
            contentType = MediaType.APPLICATION_JSON
            content = rollbackBody
        }.andExpect { status { isUnauthorized() } }
    }

    // ---- POST /prompts/{namespace}/{name}/versions/{version}/deprecate（prompt:publish） ----

    @Test
    fun `deprecate はprompt-publishスコープありなら401 403以外`() {
        val result =
            mockMvc.post("/api/v1/prompts/team/greeting/versions/1.0.0/deprecate") {
                with(jwtWithScope("prompt:publish"))
            }.andReturn()
        result.response.status shouldNotBeIn listOf(401, 403)
    }

    @Test
    fun `deprecate はスコープ無しなら403`() {
        mockMvc.post("/api/v1/prompts/team/greeting/versions/1.0.0/deprecate") {
            with(jwtWithScope("prompt:write"))
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `deprecate はトークン無しなら401`() {
        mockMvc.post("/api/v1/prompts/team/greeting/versions/1.0.0/deprecate") {}
            .andExpect { status { isUnauthorized() } }
    }

    /** [VersionController]が要求する6 Handlerを`mockk(relaxed = true)`で差し替える（DB/Testcontainers不要）。 */
    @TestConfiguration
    class Stubs {
        @Bean fun createVersionHandler(): CreateVersionHandler = mockk(relaxed = true)

        @Bean fun getVersionHandler(): GetVersionHandler = mockk(relaxed = true)

        @Bean fun diffHandler(): DiffHandler = mockk(relaxed = true)

        @Bean fun publishHandler(): PublishHandler = mockk(relaxed = true)

        @Bean fun rollbackHandler(): RollbackHandler = mockk(relaxed = true)

        @Bean fun deprecateHandler(): DeprecateHandler = mockk(relaxed = true)
    }
}
