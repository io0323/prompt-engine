package promptengine.bootstrap

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Date
import java.util.UUID

/**
 * E2Eスモークテスト（9cキックオフ必須要件、最低限: Prompt作成→Version作成→
 * submit-review→approve→publish→renderの通し）。
 *
 * **Approved化も含め全工程を実HTTPで行う**（ADR-0032、ReviewCase Aggregate実装によりADR-0016を
 * supersede）。`submit-review`/`approve`/`reject`の3エンドポイントが公開されたため、
 * かつてのRepository直接操作によるフィクスチャ（`approveVersionDirectly`）は不要になった。
 *
 * **作成者と承認者に異なるsubjectのJWTを使う**（4-eyes原則、`promptengine.review.
 * allow-self-approval`の既定値`false`が実際にガードとして機能することを確認する目的。
 * 単一subjectでE2Eを通すと、このガードが一度も通過されないまま緑になる）。
 * [AUTHOR_SUBJECT]がsubmit-reviewを行い、まず[AUTHOR_SUBJECT]自身でのapprove試行が
 * 拒否されること（自己承認防止）を確認してから、[APPROVER_SUBJECT]でのapproveが
 * 成功することを確認する。
 *
 * `SecurityConfig`の既定JwtDecoderは、実運用でのJWT誤発行を防ぐため秘密鍵を保持しない
 * （`SecurityConfig.devPublicKey`のKDoc参照）。本テストは実HTTP（`TestRestTemplate`、
 * `RANDOM_PORT`）でBearerトークンを送る必要があるため、`spring-security-test`の`jwt()`
 * postProcessor（`MockMvc`専用、実HTTPには使えない）は使えない。かわりに[TestSecurityConfig]で
 * `jwtDecoder`をテスト専有の鍵ペア（秘密鍵も保持）に差し替え、[signedJwt]でこのテストが
 * 自己署名したBearerトークンを使う。[signedJwt]は`subject`を引数に取り、作成者・承認者
 * それぞれの異なるトークンを発行できる。
 *
 * `@TestInstance(Lifecycle.PER_CLASS)`は使わない（9cで削除。原因調査）:
 * `@Testcontainers`の`@Container`静的フィールドは通常`beforeAll`相当のタイミングでコンテナを
 * 起動するが、`PER_CLASS`はJUnit5のテストインスタンスをテストクラス全体で1つだけ生成する
 * ライフサイクルへ変更するため、拡張機能のコールバック順序が変わり、Springの
 * `@DynamicPropertySource`解決（コンテナの動的マッピングポートを読む）がコンテナ起動より
 * 先に走る競合が発生していた（`IllegalStateException: Mapped port can only be obtained
 * after the container is started`、9c初回起動確認で発覚）。本クラスは`@Test`メソッドが1つのみで、
 * `@BeforeAll`等インスタンス単位の共有状態も無く、`PER_CLASS`を要する理由が無いため削除する。
 *
 * `allow-bean-definition-overriding=true`が必要な理由: [TestSecurityConfig]は`SecurityConfig`が
 * 定義する本番用`jwtDecoder`（秘密鍵を保持しない、[SecurityConfig.devPublicKey]のKDoc参照）を
 * テスト専有の鍵ペアを持つものへ意図的に差し替える。Spring Bootは既定でBean定義の重複登録を
 * エラーとするため、この意図的な上書きを許可する（本番`application.yml`には設定しない、
 * このテストに閉じたプロパティ）。
 */
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.main.allow-bean-definition-overriding=true"],
)
class PromptLifecycleSmokeTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private val promptKey = "e2e-smoke/greeting-${UUID.randomUUID()}"

    @Test
    fun `Prompt作成からVersion作成・submit-review・approve・publish・renderまでが通る`() {
        val authorHeaders = headersFor(AUTHOR_SUBJECT, "prompt:write prompt:publish prompt:read")

        createPrompt(authorHeaders).statusCode shouldBe HttpStatus.CREATED
        createVersion(authorHeaders).statusCode shouldBe HttpStatus.CREATED
        submitReview(headersFor(AUTHOR_SUBJECT, "prompt:write")).statusCode shouldBe HttpStatus.OK

        // 自己承認は拒否される（4-eyes、promptengine.review.allow-self-approvalの既定false）。
        // 作成者自身のsubjectでapproveを試み、409（INVALID_STATE_TRANSITION、'SelfApproval'）を
        // 確認する（このガードが実際に実行される経路をE2Eで通す。ADR-0032）。ステータスコードだけ
        // でなくerror.codeも確認し、別の理由（例: 別のガード）による409と混同しないようにする
        // （CodeRabbitレビュー指摘）。
        val selfApproveResponse = approve(headersFor(AUTHOR_SUBJECT, "prompt:approve"))
        selfApproveResponse.statusCode shouldBe HttpStatus.CONFLICT
        @Suppress("UNCHECKED_CAST")
        val selfApproveError = selfApproveResponse.body?.get("error") as? Map<String, Any?>
        selfApproveError?.get("code") shouldBe "INVALID_STATE_TRANSITION"

        // 承認者（作成者とは異なるsubject）によるapproveは成功する。InReview→Approved。
        approve(headersFor(APPROVER_SUBJECT, "prompt:approve")).statusCode shouldBe HttpStatus.OK

        publish(authorHeaders).statusCode shouldBe HttpStatus.OK
        verifyRenderIsDeterministic(authorHeaders)
    }

    /**
     * render（HTTP）: 状態ゲート（ADR-0024）導入後もPublished済み1.1.0なら成功する。
     * 同一入力で2回呼び、renderHashが決定論的（同じ入力なら常に同じハッシュ）であることも
     * 確認する（CodeRabbitレビュー指摘: 従来はmessagesが空でないことしか検証していなかった）。
     */
    private fun verifyRenderIsDeterministic(headers: HttpHeaders) {
        val renderBody = mapOf("versionRef" to "1.1.0", "modelProfile" to "gpt-class-large")
        val firstRenderResponse = render(renderBody, headers)
        firstRenderResponse.statusCode shouldBe HttpStatus.OK
        @Suppress("UNCHECKED_CAST")
        val messages = (firstRenderResponse.body?.get("messages") as? List<Map<String, Any?>>).orEmpty()
        messages.isNotEmpty() shouldBe true

        val secondRenderResponse = render(renderBody, headers)
        secondRenderResponse.statusCode shouldBe HttpStatus.OK
        firstRenderResponse.body?.get("renderHash") shouldBe secondRenderResponse.body?.get("renderHash")
        (firstRenderResponse.body?.get("renderHash") as? String).isNullOrBlank() shouldBe false
    }

    /** Prompt作成（HTTP）: 初版1.0.0を含めてPrompt Aggregateを作る。 */
    private fun createPrompt(headers: HttpHeaders) =
        restTemplate.exchange(
            url("/api/v1/prompts"),
            HttpMethod.POST,
            HttpEntity(
                mapOf(
                    "key" to promptKey,
                    "name" to "E2E Smoke Greeting",
                    "semVer" to "1.0.0",
                    "source" to SAMPLE_SOURCE,
                ),
                headers,
            ),
            Map::class.java,
        )

    /** Version作成（HTTP）: publish/renderで実際に使う1.1.0を専用エンドポイントで追加する。 */
    private fun createVersion(headers: HttpHeaders) =
        restTemplate.exchange(
            url("/api/v1/prompts/$promptKey/versions"),
            HttpMethod.POST,
            HttpEntity(mapOf("semVer" to "1.1.0", "source" to SAMPLE_SOURCE), headers),
            Map::class.java,
        )

    /** submit-review（HTTP）: Draft→InReview。 */
    private fun submitReview(headers: HttpHeaders) =
        restTemplate.exchange(
            url("/api/v1/prompts/$promptKey/versions/1.1.0/submit-review"),
            HttpMethod.POST,
            HttpEntity<Void>(headers),
            Map::class.java,
        )

    /** approve（HTTP）: 必要承認数（既定1）に達すればInReview→Approved。 */
    private fun approve(headers: HttpHeaders) =
        restTemplate.exchange(
            url("/api/v1/prompts/$promptKey/versions/1.1.0/approve"),
            HttpMethod.POST,
            HttpEntity<Void>(headers),
            Map::class.java,
        )

    /** publish（HTTP）: Approved化した1.1.0を公開する。 */
    private fun publish(headers: HttpHeaders) =
        restTemplate.exchange(
            url("/api/v1/prompts/$promptKey/versions/1.1.0/publish"),
            HttpMethod.POST,
            HttpEntity<Void>(headers),
            Map::class.java,
        )

    private fun render(
        body: Map<String, String>,
        headers: HttpHeaders,
    ) = restTemplate.exchange(
        url("/api/v1/prompts/$promptKey/render"),
        HttpMethod.POST,
        HttpEntity(body, headers),
        Map::class.java,
    )

    private fun headersFor(
        subject: String,
        scope: String,
    ): HttpHeaders =
        HttpHeaders().apply {
            setBearerAuth(signedJwt(scope, subject))
            contentType = MediaType.APPLICATION_JSON
        }

    private fun url(path: String): String = "http://localhost:$port$path"

    private fun signedJwt(
        scope: String,
        subject: String,
    ): String {
        val claims =
            JWTClaimsSet.Builder()
                .subject(subject)
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(60)))
                .claim("scope", scope)
                .build()
        val header = JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).build()
        val signedJwt = SignedJWT(header, claims)
        signedJwt.sign(RSASSASigner(testPrivateKey))
        return signedJwt.serialize()
    }

    @TestConfiguration
    class TestSecurityConfig {
        @Bean
        fun jwtDecoder(): JwtDecoder = NimbusJwtDecoder.withPublicKey(testPublicKey).build()
    }

    companion object {
        private const val AUTHOR_SUBJECT = "e2e-smoke-author"
        private const val APPROVER_SUBJECT = "e2e-smoke-approver"

        private const val SAMPLE_SOURCE =
            """---
pe: "1"
kind: prompt
key: e2e-smoke/greeting
name: E2E Smoke Greeting
category: e2e
---
{{#block system}}
You are a helpful assistant.
{{/block}}
{{#block user}}
Hello!
{{/block}}
"""

        private val testKeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        private val testPublicKey = testKeyPair.public as RSAPublicKey
        private val testPrivateKey = testKeyPair.private as RSAPrivateKey

        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")

        @DynamicPropertySource
        @JvmStatic
        fun registerDataSourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
