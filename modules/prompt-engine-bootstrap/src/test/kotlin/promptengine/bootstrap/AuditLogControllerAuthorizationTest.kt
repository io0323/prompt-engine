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
import promptengine.application.query.AuditLogsHandler
import promptengine.bootstrap.config.SecurityConfig
import promptengine.interfaces.rest.AuditLogController

/** `AuditLogController`の認可テスト（Issue #115、設計書§13.1）。パターンは[AliasControllerAuthorizationTest]参照。 */
@WebMvcTest(AuditLogController::class)
@Import(SecurityConfig::class, AuditLogControllerAuthorizationTest.Stubs::class)
class AuditLogControllerAuthorizationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    // ---- GET /audit-logs（audit:read） ----

    @Test
    fun `search はaudit-readスコープありなら401 403以外`() {
        val result =
            mockMvc.get("/api/v1/audit-logs") {
                with(jwtWithScope("audit:read"))
            }.andReturn()
        result.response.status shouldNotBeIn listOf(401, 403)
    }

    @Test
    fun `search はスコープ無しなら403`() {
        mockMvc.get("/api/v1/audit-logs") {
            with(jwtWithScope("prompt:read"))
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `search はトークン無しなら401`() {
        mockMvc.get("/api/v1/audit-logs") {}
            .andExpect { status { isUnauthorized() } }
    }

    /** [AuditLogController]が要求する1 Handlerを`mockk(relaxed = true)`で差し替える（DB/Testcontainers不要）。 */
    @TestConfiguration
    class Stubs {
        @Bean fun auditLogsHandler(): AuditLogsHandler = mockk(relaxed = true)
    }
}
