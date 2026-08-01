package promptengine.domain.prompt

/**
 * `GET /prompts`（設計書§13.1、検索）の一覧行（ADR-0017）。
 *
 * [status]はPublished Versionが存在すればその状態、無ければ最新Versionの状態を採用する
 * （§2.13 Version管理仕様の「Publishedは同時に1件」という前提のもと、一覧としては
 * Prompt全体を代表する単一の状態を返す必要があるため）。
 */
data class PromptSummary(
    val key: PromptKey,
    val name: String,
    val category: String?,
    val tags: List<String>,
    val status: LifecycleState,
    val latestVersion: String,
    val publishedVersion: String?,
)
