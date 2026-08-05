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
        document["openapi"] shouldBe "3.1.0"

        val paths = document["paths"] as Map<String, Any?>
        // `{namespace}/{name}`の2パス変数構成はADR-0023（`PromptKey`のルーティング衝突対策）参照。
        listOf(
            "/api/v1/prompts",
            "/api/v1/prompts/{namespace}/{name}",
            "/api/v1/prompts/{namespace}/{name}/versions",
            "/api/v1/prompts/{namespace}/{name}/versions/{version}",
            "/api/v1/prompts/{namespace}/{name}/diff",
            "/api/v1/prompts/{namespace}/{name}/versions/{version}/publish",
            "/api/v1/prompts/{namespace}/{name}/rollback",
            "/api/v1/prompts/{namespace}/{name}/versions/{version}/deprecate",
            "/api/v1/prompts/{namespace}/{name}/compile",
            "/api/v1/prompts/{namespace}/{name}/render",
            "/api/v1/prompts/{namespace}/{name}/execute",
            "/api/v1/prompts/{namespace}/{name}/aliases",
            "/api/v1/prompts/{namespace}/{name}/dependencies",
            "/api/v1/audit-logs",
            "/api/v1/metrics/prompts/{namespace}/{name}",
        ).forEach { path -> paths.keys shouldContain path }
    }

    /**
     * `PromptController.search`/`AuditLogController.search`は`@ModelAttribute`でDTOを束ねて
     * 受け取る（Spring MVC的にはフラットなクエリキーで正しくバインドされる）が、springdocに
     * `@ParameterObject`を付けないと、OpenAPI上は`params`という単一の必須オブジェクト
     * パラメータとして誤って公開されてしまう（生成クライアントが`?params=...`を送り、
     * サーバー側でバインドできない。CodeRabbitレビュー指摘）。このテストは、生成物が
     * 個別のクエリパラメータへ正しく展開されていることを明示的に検証し、`@ParameterObject`が
     * 将来剥がされる回帰を検出する。
     */
    @Suppress("UNCHECKED_CAST")
    @Test
    fun `検索系エンドポイントのクエリパラメータは個別のqueryパラメータとして公開される`() {
        val document = Yaml().load<Map<String, Any?>>(openApiFile.readText())
        val paths = document["paths"] as Map<String, Any?>

        val promptsSearchParams = queryParamNames(paths, "/api/v1/prompts", "get")
        promptsSearchParams shouldBe setOf("q", "tag", "category", "status", "page", "size")

        val auditLogSearchParams = queryParamNames(paths, "/api/v1/audit-logs", "get")
        auditLogSearchParams shouldBe setOf("aggregateId", "actor", "from", "to", "page", "size")
    }

    @Suppress("UNCHECKED_CAST")
    private fun queryParamNames(
        paths: Map<String, Any?>,
        path: String,
        method: String,
    ): Set<String> {
        val operation = (paths[path] as Map<String, Any?>)[method] as Map<String, Any?>
        val parameters = operation["parameters"] as List<Map<String, Any?>>
        return parameters.filter { it["in"] == "query" }.map { it["name"] as String }.toSet()
    }
}
