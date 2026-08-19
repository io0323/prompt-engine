package promptengine.bootstrap

import io.mockk.every
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
import promptengine.application.command.CreateGoldenDatasetHandler
import promptengine.application.command.CreateGoldenDatasetResult
import promptengine.application.query.GetGoldenDatasetHandler
import promptengine.application.query.GoldenDatasetView
import promptengine.bootstrap.config.SecurityConfig
import promptengine.interfaces.rest.GoldenDatasetController
import java.util.UUID

/**
 * `/datasets`系エンドポイントの認可（`@PreAuthorize`、設計書§13.1）を検証する
 * （ADR-0035フェーズ(d)、[BenchmarkControllerAuthorizationTest]と同じ構成）。
 */
@WebMvcTest(GoldenDatasetController::class)
@Import(SecurityConfig::class, GoldenDatasetControllerAuthorizationTest.Stubs::class)
class GoldenDatasetControllerAuthorizationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var createGoldenDatasetHandler: CreateGoldenDatasetHandler

    @Autowired
    private lateinit var getGoldenDatasetHandler: GetGoldenDatasetHandler

    private val datasetId = UUID.randomUUID()

    private val createBody =
        """{"promptKey":"team/greeting","name":"smoke-test",
            "items":[{"parameters":{"productName":"widget"},"expectedOutput":"expected"}]}"""

    // ---- POST /datasets（prompt:write） ----

    @Test
    fun `create はprompt-writeスコープありなら201`() {
        every { createGoldenDatasetHandler.handle(any()) } returns
            CreateGoldenDatasetResult(datasetId, "team/greeting", 1)

        mockMvc.post("/api/v1/datasets") {
            with(jwtWithScope("prompt:write"))
            contentType = MediaType.APPLICATION_JSON
            content = createBody
        }.andExpect { status { isCreated() } }
    }

    @Test
    fun `create はスコープ無しなら403`() {
        mockMvc.post("/api/v1/datasets") {
            with(jwtWithScope("prompt:read"))
            contentType = MediaType.APPLICATION_JSON
            content = createBody
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `create はトークン無しなら401`() {
        mockMvc.post("/api/v1/datasets") {
            contentType = MediaType.APPLICATION_JSON
            content = createBody
        }.andExpect { status { isUnauthorized() } }
    }

    // ---- GET /datasets/{id}（prompt:read） ----

    @Test
    fun `get はprompt-readスコープありなら200`() {
        every { getGoldenDatasetHandler.handle(any()) } returns
            GoldenDatasetView(datasetId, "team/greeting", "smoke-test", null, emptyList())

        mockMvc.get("/api/v1/datasets/$datasetId") {
            with(jwtWithScope("prompt:read"))
        }.andExpect { status { isOk() } }
    }

    @Test
    fun `get はスコープ無しなら403`() {
        mockMvc.get("/api/v1/datasets/$datasetId") {
            with(jwtWithScope("prompt:execute"))
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `get はトークン無しなら401`() {
        mockMvc.get("/api/v1/datasets/$datasetId") {}
            .andExpect { status { isUnauthorized() } }
    }

    /** [GoldenDatasetController]が要求する2 Handlerを`mockk()`で差し替える（DB/Testcontainers不要）。 */
    @TestConfiguration
    class Stubs {
        @Bean fun createGoldenDatasetHandler(): CreateGoldenDatasetHandler = mockk()

        @Bean fun getGoldenDatasetHandler(): GetGoldenDatasetHandler = mockk()
    }
}
