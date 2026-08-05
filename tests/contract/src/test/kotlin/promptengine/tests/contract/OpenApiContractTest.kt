package promptengine.tests.contract

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * `api/openapi.yaml`（Issue #13、`.github/workflows/contract.yml`が実装との差分を検出する
 * 対象ファイル）の構造検証（P9c）。
 *
 * springdoc生成物との実際の突合せ（実装が変わったのにファイルを更新し忘れていないか）は
 * Docker上でアプリを起動する必要があり、`contract.yml`（CI）側の責務とする。本テストは
 * リポジトリにコミットされたファイル自体が壊れていないか（§13.1のエンドポイントが
 * 最低限反映されているか）を、Dockerを使わずに素早く検証する。
 */
class OpenApiContractTest {
    private val openApiFile: File
        get() {
            val candidates =
                listOf(
                    File("../../api/openapi.yaml"),
                    File("api/openapi.yaml"),
                )
            return candidates.firstOrNull { it.exists() }
                ?: error(
                    "api/openapi.yaml が見つかりません。`./gradlew " +
                        ":modules:prompt-engine-bootstrap:generateOpenApiDocs` を実行してコミットしてください。",
                )
        }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `api openapi yamlは有効なOpenAPIドキュメントであり設計書13-1の主要エンドポイントを含む`() {
        val document = Yaml().load<Map<String, Any?>>(openApiFile.readText())
        document["openapi"] shouldBe "3.0.1"

        val paths = document["paths"] as Map<String, Any?>
        listOf(
            "/api/v1/prompts",
            "/api/v1/prompts/{key}",
            "/api/v1/prompts/{key}/versions",
            "/api/v1/prompts/{key}/versions/{version}",
            "/api/v1/prompts/{key}/diff",
            "/api/v1/prompts/{key}/versions/{version}/publish",
            "/api/v1/prompts/{key}/rollback",
            "/api/v1/prompts/{key}/versions/{version}/deprecate",
            "/api/v1/prompts/{key}/compile",
            "/api/v1/prompts/{key}/render",
            "/api/v1/prompts/{key}/execute",
            "/api/v1/prompts/{key}/aliases",
            "/api/v1/prompts/{key}/dependencies",
            "/api/v1/audit-logs",
            "/api/v1/metrics/prompts/{key}",
        ).forEach { path -> paths.keys shouldContain path }
    }
}
