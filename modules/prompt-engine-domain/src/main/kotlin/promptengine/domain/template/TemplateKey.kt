package promptengine.domain.template

/**
 * Templateの識別子。`namespace/name` 形式（[promptengine.domain.prompt.PromptKey] と同じ規約）。
 */
data class TemplateKey(val value: String) {
    init {
        require(PATTERN.matches(value)) { "invalid TemplateKey format: $value" }
    }

    private companion object {
        val PATTERN = Regex("[a-z0-9-]+(/[a-z0-9-]+)+")
    }
}
