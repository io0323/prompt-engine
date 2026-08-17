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
import promptengine.application.command.ApproveHandler
import promptengine.application.command.RejectHandler
import promptengine.application.command.SubmitReviewHandler
import promptengine.bootstrap.config.SecurityConfig
import promptengine.interfaces.rest.ReviewController

/** `ReviewController`の認可テスト（Issue #115、設計書§13.1、Governanceコンテキスト）。パターンは[AliasControllerAuthorizationTest]参照。 */
@WebMvcTest(ReviewController::class)
@Import(SecurityConfig::class, ReviewControllerAuthorizationTest.Stubs::class)
class ReviewControllerAuthorizationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    private val rejectBody = """{"comment":"見直してください"}"""

    // ---- POST /prompts/{namespace}/{name}/versions/{version}/submit-review（prompt:write） ----

    @Test
    fun `submitReview はprompt-writeスコープありなら401 403以外`() {
        val result =
            mockMvc.post("/api/v1/prompts/team/greeting/versions/1.0.0/submit-review") {
                with(jwtWithScope("prompt:write"))
            }.andReturn()
        result.response.status shouldNotBeIn listOf(401, 403)
    }

    @Test
    fun `submitReview はスコープ無しなら403`() {
        mockMvc.post("/api/v1/prompts/team/greeting/versions/1.0.0/submit-review") {
            with(jwtWithScope("prompt:read"))
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `submitReview はトークン無しなら401`() {
        mockMvc.post("/api/v1/prompts/team/greeting/versions/1.0.0/submit-review") {}
            .andExpect { status { isUnauthorized() } }
    }

    // ---- POST /prompts/{namespace}/{name}/versions/{version}/approve（prompt:approve） ----

    @Test
    fun `approve はprompt-approveスコープありなら401 403以外`() {
        val result =
            mockMvc.post("/api/v1/prompts/team/greeting/versions/1.0.0/approve") {
                with(jwtWithScope("prompt:approve"))
            }.andReturn()
        result.response.status shouldNotBeIn listOf(401, 403)
    }

    @Test
    fun `approve はスコープ無しなら403`() {
        mockMvc.post("/api/v1/prompts/team/greeting/versions/1.0.0/approve") {
            with(jwtWithScope("prompt:write"))
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `approve はトークン無しなら401`() {
        mockMvc.post("/api/v1/prompts/team/greeting/versions/1.0.0/approve") {}
            .andExpect { status { isUnauthorized() } }
    }

    // ---- POST /prompts/{namespace}/{name}/versions/{version}/reject（prompt:review） ----

    @Test
    fun `reject はprompt-reviewスコープありなら401 403以外`() {
        val result =
            mockMvc.post("/api/v1/prompts/team/greeting/versions/1.0.0/reject") {
                with(jwtWithScope("prompt:review"))
                contentType = MediaType.APPLICATION_JSON
                content = rejectBody
            }.andReturn()
        result.response.status shouldNotBeIn listOf(401, 403)
    }

    @Test
    fun `reject はスコープ無しなら403`() {
        mockMvc.post("/api/v1/prompts/team/greeting/versions/1.0.0/reject") {
            with(jwtWithScope("prompt:write"))
            contentType = MediaType.APPLICATION_JSON
            content = rejectBody
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `reject はトークン無しなら401`() {
        mockMvc.post("/api/v1/prompts/team/greeting/versions/1.0.0/reject") {
            contentType = MediaType.APPLICATION_JSON
            content = rejectBody
        }.andExpect { status { isUnauthorized() } }
    }

    /** [ReviewController]が要求する3 Handlerを`mockk(relaxed = true)`で差し替える（DB/Testcontainers不要）。 */
    @TestConfiguration
    class Stubs {
        @Bean fun submitReviewHandler(): SubmitReviewHandler = mockk(relaxed = true)

        @Bean fun approveHandler(): ApproveHandler = mockk(relaxed = true)

        @Bean fun rejectHandler(): RejectHandler = mockk(relaxed = true)
    }
}
