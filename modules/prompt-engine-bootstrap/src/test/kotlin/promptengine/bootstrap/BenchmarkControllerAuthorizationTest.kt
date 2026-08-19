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
import promptengine.application.command.CancelBenchmarkHandler
import promptengine.application.command.CancelBenchmarkResult
import promptengine.application.command.CreateBenchmarkHandler
import promptengine.application.command.CreateBenchmarkResult
import promptengine.application.query.BenchmarkProgressSummary
import promptengine.application.query.BenchmarkResultsView
import promptengine.application.query.BenchmarkView
import promptengine.application.query.GetBenchmarkHandler
import promptengine.application.query.GetBenchmarkResultsHandler
import promptengine.bootstrap.config.SecurityConfig
import promptengine.interfaces.rest.BenchmarkController
import java.util.UUID

/**
 * `/benchmarks`系エンドポイントの認可（`@PreAuthorize`、設計書§13.1）を検証する
 * （ADR-0035フェーズ(d)、Issue #115で確立した軽量パターンを踏襲）。
 * [ExperimentControllerAuthorizationTest]と同じ構成: `@WebMvcTest`でWeb層のみをロードし、
 * `@Import(SecurityConfig::class)`＋[jwtWithScope]（`AuthorizationTestSupport.kt`）で
 * 実`CiapAuthAdapter`を経由した認可判定を検証する。Application層のHandlerは[Stubs]で
 * `mockk()`に差し替え、DB・Testcontainersを一切必要としない。
 */
@WebMvcTest(BenchmarkController::class)
@Import(SecurityConfig::class, BenchmarkControllerAuthorizationTest.Stubs::class)
class BenchmarkControllerAuthorizationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var createBenchmarkHandler: CreateBenchmarkHandler

    @Autowired
    private lateinit var getBenchmarkHandler: GetBenchmarkHandler

    @Autowired
    private lateinit var cancelBenchmarkHandler: CancelBenchmarkHandler

    @Autowired
    private lateinit var getBenchmarkResultsHandler: GetBenchmarkResultsHandler

    private val benchmarkId = UUID.randomUUID()
    private val datasetId = UUID.randomUUID()

    private val createBody =
        """{"promptKey":"team/greeting","datasetId":"$datasetId",
            "targets":[{"semVer":"1.0.0"}],"metrics":["Accuracy"],"nRepetitions":3}"""

    private val progress =
        BenchmarkProgressSummary(totalItems = 0, completedItems = 0, failedItems = 0, pendingItems = 0)

    // ---- POST /benchmarks（prompt:write） ----

    @Test
    fun `create はprompt-writeスコープありなら201`() {
        every { createBenchmarkHandler.handle(any()) } returns
            CreateBenchmarkResult(benchmarkId, "team/greeting", "Pending", 3)

        mockMvc.post("/api/v1/benchmarks") {
            with(jwtWithScope("prompt:write"))
            contentType = MediaType.APPLICATION_JSON
            content = createBody
        }.andExpect { status { isCreated() } }
    }

    @Test
    fun `create はスコープ無しなら403`() {
        mockMvc.post("/api/v1/benchmarks") {
            with(jwtWithScope("prompt:read"))
            contentType = MediaType.APPLICATION_JSON
            content = createBody
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `create はトークン無しなら401`() {
        mockMvc.post("/api/v1/benchmarks") {
            contentType = MediaType.APPLICATION_JSON
            content = createBody
        }.andExpect { status { isUnauthorized() } }
    }

    // ---- GET /benchmarks/{id}（prompt:read） ----

    @Test
    fun `get はprompt-readスコープありなら200`() {
        every { getBenchmarkHandler.handle(any()) } returns
            BenchmarkView(
                benchmarkId,
                "team/greeting",
                datasetId,
                emptyList(),
                setOf("Accuracy"),
                3,
                null,
                "Pending",
                3,
                progress,
            )

        mockMvc.get("/api/v1/benchmarks/$benchmarkId") {
            with(jwtWithScope("prompt:read"))
        }.andExpect { status { isOk() } }
    }

    @Test
    fun `get はスコープ無しなら403`() {
        mockMvc.get("/api/v1/benchmarks/$benchmarkId") {
            with(jwtWithScope("prompt:execute"))
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `get はトークン無しなら401`() {
        mockMvc.get("/api/v1/benchmarks/$benchmarkId") {}
            .andExpect { status { isUnauthorized() } }
    }

    // ---- POST /benchmarks/{id}/cancel（prompt:publish） ----

    @Test
    fun `cancel はprompt-publishスコープありなら200`() {
        every { cancelBenchmarkHandler.handle(any()) } returns CancelBenchmarkResult(benchmarkId, "Cancelling")

        mockMvc.post("/api/v1/benchmarks/$benchmarkId/cancel") {
            with(jwtWithScope("prompt:publish"))
        }.andExpect { status { isOk() } }
    }

    @Test
    fun `cancel はスコープ無しなら403`() {
        mockMvc.post("/api/v1/benchmarks/$benchmarkId/cancel") {
            with(jwtWithScope("prompt:read"))
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `cancel はトークン無しなら401`() {
        mockMvc.post("/api/v1/benchmarks/$benchmarkId/cancel") {}
            .andExpect { status { isUnauthorized() } }
    }

    // ---- GET /benchmarks/{id}/results（prompt:read） ----

    @Test
    fun `results はprompt-readスコープありなら200`() {
        every { getBenchmarkResultsHandler.handle(any()) } returns
            BenchmarkResultsView(benchmarkId, "Completed", emptyList())

        mockMvc.get("/api/v1/benchmarks/$benchmarkId/results") {
            with(jwtWithScope("prompt:read"))
        }.andExpect { status { isOk() } }
    }

    @Test
    fun `results はスコープ無しなら403`() {
        mockMvc.get("/api/v1/benchmarks/$benchmarkId/results") {
            with(jwtWithScope("prompt:execute"))
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `results はトークン無しなら401`() {
        mockMvc.get("/api/v1/benchmarks/$benchmarkId/results") {}
            .andExpect { status { isUnauthorized() } }
    }

    /** [BenchmarkController]が要求する4 Handlerを`mockk()`で差し替える（DB/Testcontainers不要）。 */
    @TestConfiguration
    class Stubs {
        @Bean fun createBenchmarkHandler(): CreateBenchmarkHandler = mockk()

        @Bean fun getBenchmarkHandler(): GetBenchmarkHandler = mockk()

        @Bean fun cancelBenchmarkHandler(): CancelBenchmarkHandler = mockk()

        @Bean fun getBenchmarkResultsHandler(): GetBenchmarkResultsHandler = mockk()
    }
}
