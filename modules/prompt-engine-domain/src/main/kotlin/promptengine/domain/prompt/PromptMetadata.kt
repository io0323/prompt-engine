package promptengine.domain.prompt

/**
 * `prompts`テーブル（設計書§12）のname/category/description/tags列に対応する、
 * `Prompt` Aggregateの不変条件とは独立した表示・検索用属性（ADR-0020）。
 */
data class PromptMetadata(
    val key: PromptKey,
    val name: String,
    val category: String? = null,
    val description: String? = null,
    val tags: List<String> = emptyList(),
) {
    init {
        require(name.isNotBlank()) { "name must not be blank" }
    }
}
