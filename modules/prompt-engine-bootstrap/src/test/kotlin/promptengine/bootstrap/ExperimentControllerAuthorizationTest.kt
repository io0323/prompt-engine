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
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import promptengine.application.command.CreateExperimentHandler
import promptengine.application.command.CreateExperimentResult
import promptengine.application.command.PromoteExperimentHandler
import promptengine.application.command.PromoteExperimentResult
import promptengine.application.command.StartExperimentHandler
import promptengine.application.command.StartExperimentResult
import promptengine.application.command.StopExperimentHandler
import promptengine.application.command.StopExperimentResult
import promptengine.application.command.UpdateExperimentTrafficHandler
import promptengine.application.command.UpdateExperimentTrafficResult
import promptengine.application.query.ExperimentResultsView
import promptengine.application.query.GetExperimentResultsHandler
import promptengine.bootstrap.config.SecurityConfig
import promptengine.interfaces.rest.ExperimentController
import java.util.UUID

/**
 * `/experiments`系エンドポイントの認可（`@PreAuthorize`、設計書§13.1）を検証する
 * （ADR-0034、ユーザー指摘対応）。
 *
 * P9cの実装プロンプト（`docs/prompts/p9c.md`）は「スコープ有り→成功/スコープ無し403/
 * トークン無し401」の網羅を求めていたが、実際にはこの粒度のテストは追加されておらず
 * （`CiapAuthAdapterTest`はJWT→Authority変換のみ、`MetricsControllerTest`はController
 * メソッド直接呼出でHTTP/認可を経由しない、`PromptLifecycleSmokeTest`は重いE2E）、
 * 本クラスがこのリポジトリで初めて軽量な認可マトリクステストを追加する
 * （`spring-security-test`は`prompt-engine-interface`/`prompt-engine-bootstrap`双方の
 * `build.gradle.kts`に既に宣言されていたが未使用だった）。
 *
 * `@WebMvcTest(ExperimentController::class)`でWeb層のみをロードし、
 * `@Import(SecurityConfig::class)`で`@EnableMethodSecurity`・`SecurityFilterChain`・
 * `CiapAuthAdapter`（scopeクレーム→GrantedAuthority変換）を組み込む。Application層の
 * 各Handlerは[Stubs]で`mockk()`に差し替えるため、DB・Testcontainersを一切必要としない。
 *
 * `SecurityMockMvcRequestPostProcessors.jwt()`は`SecurityConfig`の実`JwtDecoder`
 * （署名検証）を経由せずモックの`Jwt`を認証済みとして注入する。**ただし`jwt()`は既定では
 * `scope`クレームを`CiapAuthAdapter`ではなくSpring標準の`JwtGrantedAuthoritiesConverter`
 * （`SCOPE_`接頭辞を付与する）で`GrantedAuthority`化するため、`.claim("scope", ...)`だけでは
 * `@PreAuthorize("hasAuthority('prompt:write')")`（接頭辞無し、`CiapAuthAdapter`のKDoc参照）
 * と一致しない**（実装当初はこの差異に気づかず、スコープ有りのテストが403で落ちた）。
 * [jwtWithScope]（`AuthorizationTestSupport.kt`、Issue #115で他の`*AuthorizationTest`と
 * 共有するため切り出した）は`JwtRequestPostProcessor.authorities(Converter)`で明示的に実
 * `CiapAuthAdapter.convert`を経由させ、`@PreAuthorize`の判定が実際のコードパスを
 * そのまま通るようにする。
 */
@WebMvcTest(ExperimentController::class)
@Import(SecurityConfig::class, ExperimentControllerAuthorizationTest.Stubs::class)
class ExperimentControllerAuthorizationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var createExperimentHandler: CreateExperimentHandler

    @Autowired
    private lateinit var startExperimentHandler: StartExperimentHandler

    @Autowired
    private lateinit var stopExperimentHandler: StopExperimentHandler

    @Autowired
    private lateinit var updateExperimentTrafficHandler: UpdateExperimentTrafficHandler

    @Autowired
    private lateinit var promoteExperimentHandler: PromoteExperimentHandler

    @Autowired
    private lateinit var getExperimentResultsHandler: GetExperimentResultsHandler

    private val experimentId = UUID.randomUUID()

    private val createBody =
        """{"promptKey":"team/greeting","type":"AB","variants":[
            {"name":"control","semVer":"1.0.0","weightPct":50},
            {"name":"treatment","semVer":"1.0.0","weightPct":50}]}"""
    private val trafficBody = """{"weights":[{"name":"control","weightPct":30},{"name":"treatment","weightPct":70}]}"""
    private val promoteBody = """{"winnerVariantName":"control"}"""

    // ---- POST /experiments（prompt:write） ----

    @Test
    fun `create はprompt-writeスコープありなら201`() {
        every { createExperimentHandler.handle(any()) } returns CreateExperimentResult(experimentId, "team/greeting")

        mockMvc.post("/api/v1/experiments") {
            with(jwtWithScope("prompt:write"))
            contentType = MediaType.APPLICATION_JSON
            content = createBody
        }.andExpect { status { isCreated() } }
    }

    @Test
    fun `create はスコープ無しなら403`() {
        mockMvc.post("/api/v1/experiments") {
            with(jwtWithScope("prompt:read"))
            contentType = MediaType.APPLICATION_JSON
            content = createBody
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `create はトークン無しなら401`() {
        mockMvc.post("/api/v1/experiments") {
            contentType = MediaType.APPLICATION_JSON
            content = createBody
        }.andExpect { status { isUnauthorized() } }
    }

    // ---- POST /experiments/{id}/start（prompt:publish） ----

    @Test
    fun `start はprompt-publishスコープありなら200`() {
        every { startExperimentHandler.handle(any()) } returns StartExperimentResult(experimentId)

        mockMvc.post("/api/v1/experiments/$experimentId/start") {
            with(jwtWithScope("prompt:publish"))
        }.andExpect { status { isOk() } }
    }

    @Test
    fun `start はスコープ無しなら403`() {
        mockMvc.post("/api/v1/experiments/$experimentId/start") {
            with(jwtWithScope("prompt:read"))
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `start はトークン無しなら401`() {
        mockMvc.post("/api/v1/experiments/$experimentId/start") {}
            .andExpect { status { isUnauthorized() } }
    }

    // ---- POST /experiments/{id}/stop（prompt:publish） ----

    @Test
    fun `stop はprompt-publishスコープありなら200`() {
        every { stopExperimentHandler.handle(any()) } returns StopExperimentResult(experimentId)

        mockMvc.post("/api/v1/experiments/$experimentId/stop") {
            with(jwtWithScope("prompt:publish"))
        }.andExpect { status { isOk() } }
    }

    @Test
    fun `stop はスコープ無しなら403`() {
        mockMvc.post("/api/v1/experiments/$experimentId/stop") {
            with(jwtWithScope("prompt:read"))
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `stop はトークン無しなら401`() {
        mockMvc.post("/api/v1/experiments/$experimentId/stop") {}
            .andExpect { status { isUnauthorized() } }
    }

    // ---- PATCH /experiments/{id}/traffic（prompt:publish） ----

    @Test
    fun `traffic はprompt-publishスコープありなら200`() {
        every { updateExperimentTrafficHandler.handle(any()) } returns UpdateExperimentTrafficResult(experimentId)

        mockMvc.patch("/api/v1/experiments/$experimentId/traffic") {
            with(jwtWithScope("prompt:publish"))
            contentType = MediaType.APPLICATION_JSON
            content = trafficBody
        }.andExpect { status { isOk() } }
    }

    @Test
    fun `traffic はスコープ無しなら403`() {
        mockMvc.patch("/api/v1/experiments/$experimentId/traffic") {
            with(jwtWithScope("prompt:read"))
            contentType = MediaType.APPLICATION_JSON
            content = trafficBody
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `traffic はトークン無しなら401`() {
        mockMvc.patch("/api/v1/experiments/$experimentId/traffic") {
            contentType = MediaType.APPLICATION_JSON
            content = trafficBody
        }.andExpect { status { isUnauthorized() } }
    }

    // ---- GET /experiments/{id}/results（prompt:read） ----

    @Test
    fun `results はprompt-readスコープありなら200`() {
        every { getExperimentResultsHandler.handle(any()) } returns
            ExperimentResultsView(experimentId, "team/greeting", "Running", "control", emptyList(), emptyList())

        mockMvc.get("/api/v1/experiments/$experimentId/results") {
            with(jwtWithScope("prompt:read"))
        }.andExpect { status { isOk() } }
    }

    @Test
    fun `results はスコープ無しなら403`() {
        mockMvc.get("/api/v1/experiments/$experimentId/results") {
            with(jwtWithScope("prompt:execute"))
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `results はトークン無しなら401`() {
        mockMvc.get("/api/v1/experiments/$experimentId/results") {}
            .andExpect { status { isUnauthorized() } }
    }

    // ---- POST /experiments/{id}/promote（prompt:publish） ----

    @Test
    fun `promote はprompt-publishスコープありなら200`() {
        every { promoteExperimentHandler.handle(any()) } returns
            PromoteExperimentResult(experimentId, "team/greeting", "1.0.0")

        mockMvc.post("/api/v1/experiments/$experimentId/promote") {
            with(jwtWithScope("prompt:publish"))
            contentType = MediaType.APPLICATION_JSON
            content = promoteBody
        }.andExpect { status { isOk() } }
    }

    @Test
    fun `promote はスコープ無しなら403`() {
        mockMvc.post("/api/v1/experiments/$experimentId/promote") {
            with(jwtWithScope("prompt:read"))
            contentType = MediaType.APPLICATION_JSON
            content = promoteBody
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `promote はトークン無しなら401`() {
        mockMvc.post("/api/v1/experiments/$experimentId/promote") {
            contentType = MediaType.APPLICATION_JSON
            content = promoteBody
        }.andExpect { status { isUnauthorized() } }
    }

    /** [ExperimentController]が要求する6 Handlerを`mockk()`で差し替える（DB/Testcontainers不要）。 */
    @TestConfiguration
    class Stubs {
        @Bean fun createExperimentHandler(): CreateExperimentHandler = mockk()

        @Bean fun startExperimentHandler(): StartExperimentHandler = mockk()

        @Bean fun stopExperimentHandler(): StopExperimentHandler = mockk()

        @Bean fun updateExperimentTrafficHandler(): UpdateExperimentTrafficHandler = mockk()

        @Bean fun promoteExperimentHandler(): PromoteExperimentHandler = mockk()

        @Bean fun getExperimentResultsHandler(): GetExperimentResultsHandler = mockk()
    }
}
