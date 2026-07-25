package promptengine.domain.prompt

/**
 * Promptの識別子。`namespace/name` 形式（設計書§4.4）。
 */
data class PromptKey(val value: String) {
    init {
        require(PATTERN.matches(value)) { "invalid PromptKey format: $value" }
    }

    val namespace: String get() = value.substringBeforeLast('/')
    val name: String get() = value.substringAfterLast('/')

    private companion object {
        val PATTERN = Regex("[a-z0-9-]+(/[a-z0-9-]+)+")
    }
}
