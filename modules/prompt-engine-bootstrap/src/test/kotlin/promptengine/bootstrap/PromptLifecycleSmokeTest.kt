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
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.PromptRepository
import promptengine.domain.shared.SemVer
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Date
import java.util.UUID

/**
 * E2Eスモークテスト（9cキックオフ必須要件、最低限: Prompt作成→Version作成→publish→renderの通し）。
 *
 * **Approved化はHTTP経由ではなくRepository直接操作で行う**: `publish`は`Approved`状態の
 * Versionにしか実行できないが、Draft→InReview→Approvedへ遷移させる`submit-review`/
 * `approve`/`reject`の3エンドポイントはADR-0016によりM2スコープへ見送られている
 * （`ReviewCase` Aggregateを経由しない実装だと、この意思決定上重要な状態遷移が監査ログに
 * 記録されないガバナンス上の懸念のため。GitHub Issue #9参照）。このためM1のAPIサーフェス
 * だけでは`publish`前提の`Approved`状態を実HTTPだけで作ることができない（9c、E2E初回確認で
 * 発覚）。[approveVersionDirectly]でRepositoryを直接操作してテストフィクスチャとして
 * Approved状態を先回りし、`publish`以降（`publish`・`render`という実装済みエンドポイント
 * 自体の動作）は引き続き実HTTPで検証する。
 *
 * `SecurityConfig`の既定JwtDecoderは、実運用でのJWT誤発行を防ぐため秘密鍵を保持しない
 * （`SecurityConfig.devPublicKey`のKDoc参照）。本テストは実HTTP（`TestRestTemplate`、
 * `RANDOM_PORT`）でBearerトークンを送る必要があるため、`spring-security-test`の`jwt()`
 * postProcessor（`MockMvc`専用、実HTTPには使えない）は使えない。かわりに[TestSecurityConfig]で
 * `jwtDecoder`をテスト専有の鍵ペア（秘密鍵も保持）に差し替え、[signedJwt]でこのテストが
 * 自己署名したBearerトークンを使う。
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

    @Autowired
    private lateinit var promptRepository: PromptRepository

    private val promptKey = "e2e-smoke/greeting-${UUID.randomUUID()}"

    @Test
    fun `Prompt作成からVersion作成・Approved化・publish・renderまでが通る`() {
        val headers =
            HttpHeaders().apply {
                setBearerAuth(signedJwt("prompt:write prompt:publish prompt:read"))
                contentType = org.springframework.http.MediaType.APPLICATION_JSON
            }

        // 1. Prompt作成（HTTP）: 初版1.0.0を含めてPrompt Aggregateを作る。
        val createBody =
            mapOf(
                "key" to promptKey,
                "name" to "E2E Smoke Greeting",
                "semVer" to "1.0.0",
                "source" to SAMPLE_SOURCE,
            )
        val createResponse =
            restTemplate.exchange(
                url("/api/v1/prompts"),
                HttpMethod.POST,
                HttpEntity(createBody, headers),
                Map::class.java,
            )
        createResponse.statusCode shouldBe HttpStatus.CREATED

        // 2. Version作成（HTTP）: publish/renderで実際に使う1.1.0を専用エンドポイントで追加する。
        val createVersionBody = mapOf("semVer" to "1.1.0", "source" to SAMPLE_SOURCE)
        val createVersionResponse =
            restTemplate.exchange(
                url("/api/v1/prompts/$promptKey/versions"),
                HttpMethod.POST,
                HttpEntity(createVersionBody, headers),
                Map::class.java,
            )
        createVersionResponse.statusCode shouldBe HttpStatus.CREATED

        // 3. Approved化（Repository直接。クラスKDoc「Approved化はHTTP経由ではなく...」参照）。
        approveVersionDirectly(promptKey, SemVer(1, 1, 0))

        // 4. publish（HTTP）: Approved化した1.1.0を公開する。
        val publishResponse =
            restTemplate.exchange(
                url("/api/v1/prompts/$promptKey/versions/1.1.0/publish"),
                HttpMethod.POST,
                HttpEntity<Void>(headers),
                Map::class.java,
            )
        publishResponse.statusCode shouldBe HttpStatus.OK

        // 5. render（HTTP）: 状態ゲート（ADR-0024）導入後もPublished済み1.1.0なら成功する。
        val renderBody = mapOf("versionRef" to "1.1.0", "modelProfile" to "gpt-class-large")
        val renderResponse =
            restTemplate.exchange(
                url("/api/v1/prompts/$promptKey/render"),
                HttpMethod.POST,
                HttpEntity(renderBody, headers),
                Map::class.java,
            )
        renderResponse.statusCode shouldBe HttpStatus.OK
        @Suppress("UNCHECKED_CAST")
        val messages = (renderResponse.body?.get("messages") as? List<Map<String, Any?>>).orEmpty()
        messages.isNotEmpty() shouldBe true
    }

    /**
     * `submit-review`/`approve`相当をRepository直接操作で行う（クラスKDoc参照、ADR-0016・
     * GitHub Issue #9によりHTTP経由のエンドポイントとしては存在しない）。
     */
    private fun approveVersionDirectly(
        key: String,
        semVer: SemVer,
    ) {
        val prompt = requireNotNull(promptRepository.findByKey(PromptKey(key))) { "prompt not found: $key" }
        val inReview = prompt.submitForReview(semVer, validationPassed = true)
        val approved = inReview.approve(semVer, approvalCount = 1, requiredApprovalCount = 1)
        promptRepository.save(approved)
    }

    private fun url(path: String): String = "http://localhost:$port$path"

    private fun signedJwt(scope: String): String {
        val claims =
            JWTClaimsSet.Builder()
                .subject("e2e-smoke-test-client")
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
