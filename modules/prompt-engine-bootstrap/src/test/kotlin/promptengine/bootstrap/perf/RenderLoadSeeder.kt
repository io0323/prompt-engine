package promptengine.bootstrap.perf

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import promptengine.domain.event.EventContext
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.PromptRepository
import promptengine.domain.shared.SemVer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.io.path.writeText

/**
 * P11の性能測定（NFR-003、README「性能測定」節）専用のシードツール。`./gradlew test`には
 * 含めない（`@Tag("perf")`、`prompt-engine-bootstrap/build.gradle.kts`で除外）ため、
 * README記載のコマンドで明示的に実行する。
 *
 * 通常のテストではなく、実行中の別プロセス（`deploy/docker/Dockerfile`でビルドした
 * コンテナ、性能測定対象そのもの）に対してHTTP経由でPrompt/Versionを作成し、`render`で
 * 測定する対象データを用意する。承認（Approved）・publish状態への遷移はHTTP経路が無い
 * （submitForReview/approveはADR-0016・Issue #9、
 * [promptengine.bootstrap.PromptLifecycleSmokeTest]と同じ制約。publish自体は
 * エンドポイントがあるが、承認をHTTP側で作れない以上どのみちここでまとめて行う方が単純）
 * ため、このJVM自身が`PromptRepository`（対象コンテナと
 * 同じPostgreSQLに接続する別のSpringコンテキスト）を直接操作して行う。`webEnvironment`は
 * 既定（MOCK）のままにする — `NONE`にすると`SecurityConfig.securityFilterChain`が要求する
 * `HttpSecurity`（Servlet Web ApplicationContext前提）が解決できずコンテキスト起動に
 * 失敗するため（実測、Servletコンテキストは要るが実ポートは割り当てない）。
 *
 * 通常は`tools/perf/render_load_test.sh`から必要な環境変数
 * （`PE_DATASOURCE_URL`・`PERF_APP_BASE_URL`・`PERF_JWT`）を設定したうえで
 * 自動的に呼び出される（README「性能測定」節）。
 * 手動実行例:
 * ```
 * PE_DATASOURCE_URL=jdbc:postgresql://localhost:55432/prompt_engine \
 * PE_DATASOURCE_USERNAME=prompt_engine PE_DATASOURCE_PASSWORD=prompt_engine \
 * PERF_APP_BASE_URL=http://localhost:8080 \
 * PERF_JWT=<DevJwksが発行したBearerトークン> \
 * ./gradlew :modules:prompt-engine-bootstrap:test --tests "*.RenderLoadSeeder" -DincludeTags=perf
 * ```
 */
@Tag("perf")
@SpringBootTest
@ActiveProfiles("dev")
class RenderLoadSeeder
    @Autowired
    constructor(
        private val promptRepository: PromptRepository,
    ) {
        private val objectMapper = ObjectMapper()

        @Test
        fun `対象コンテナへPublished状態のPromptVersionを1件用意する`() {
            val baseUrl = System.getenv("PERF_APP_BASE_URL") ?: "http://localhost:8080"
            val jwt =
                requireNotNull(System.getenv("PERF_JWT")) { "PERF_JWT環境変数が未設定です（DevJwks.javaの出力を参照）" }
            val promptKey = "perf/render-load-${UUID.randomUUID()}"
            val semVerText = "1.0.0"
            val client = HttpClient.newHttpClient()

            val createBody =
                objectMapper.writeValueAsString(
                    mapOf(
                        "key" to promptKey,
                        "name" to "Perf Load Prompt",
                        "semVer" to semVerText,
                        "source" to PRODUCTION_SCALE_SOURCE,
                    ),
                )
            post(client, "$baseUrl/api/v1/prompts", jwt, createBody).expectStatus(201, "create prompt")

            approveAndPublishDirectly(promptKey, SemVer(1, 0, 0))

            val outputPath = System.getenv("PERF_SEED_OUTPUT") ?: "/tmp/render-load-seed.env"
            Path.of(outputPath).writeText("PERF_PROMPT_KEY=$promptKey\nPERF_SEMVER=$semVerText\n")
            println("SEEDED promptKey=$promptKey semVer=$semVerText output=$outputPath")
        }

        private fun approveAndPublishDirectly(
            key: String,
            version: SemVer,
        ) {
            val prompt = requireNotNull(promptRepository.findByKey(PromptKey(key))) { "prompt not found: $key" }
            val context =
                EventContext(
                    actor = "perf-seed-tool",
                    traceId = UUID.randomUUID().toString(),
                    occurredAt = Instant.now(),
                )
            val inReview = prompt.submitForReview(version, validationPassed = true)
            val approved = inReview.approve(version, approvalCount = 1, requiredApprovalCount = 1)
            val (published, _) = approved.publish(version, allDependenciesPublished = true, context = context)
            promptRepository.save(published)
        }

        private fun post(
            client: HttpClient,
            url: String,
            jwt: String,
            body: String?,
        ): HttpResponse<String> {
            val publisher =
                if (body != null) HttpRequest.BodyPublishers.ofString(body) else HttpRequest.BodyPublishers.noBody()
            val request =
                HttpRequest.newBuilder(URI.create(url))
                    .header("Authorization", "Bearer $jwt")
                    .header("Content-Type", "application/json")
                    .POST(publisher)
                    .build()
            return client.send(request, HttpResponse.BodyHandlers.ofString())
        }

        private fun HttpResponse<String>.expectStatus(
            expected: Int,
            step: String,
        ) {
            check(statusCode() == expected) { "$step failed: HTTP ${statusCode()} ${body()}" }
        }

        companion object {
            /**
             * 性能測定用の「本番相当サイズ」fixture（承認2点目d）。長いsystemブロック＋
             * few-shot例を複数含む。
             * `tests/prompt-regression/fixtures/valid/04-production-scale-support-agent.prompt`
             * の複製（クラスパス上で参照するためこのモジュールの`test/resources`配下にも
             * 置いている）。内容を変更する場合は両方を更新する必要がある。
             */
            private val PRODUCTION_SCALE_SOURCE =
                RenderLoadSeeder::class.java
                    .getResourceAsStream("/perf-fixtures/04-production-scale-support-agent.prompt")
                    ?.bufferedReader()
                    ?.readText()
                    ?: error("perf-fixtures/04-production-scale-support-agent.prompt がクラスパスに無い")
        }
    }
