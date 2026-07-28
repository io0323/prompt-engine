package promptengine.domain.fragment

/**
 * Fragmentの識別子。`namespace/name` 形式（[promptengine.domain.prompt.PromptKey] と同じ規約）。
 */
data class FragmentKey(val value: String) {
    init {
        require(PATTERN.matches(value)) { "invalid FragmentKey format: $value" }
    }

    private companion object {
        val PATTERN = Regex("[a-z0-9-]+(/[a-z0-9-]+)+")
    }
}
