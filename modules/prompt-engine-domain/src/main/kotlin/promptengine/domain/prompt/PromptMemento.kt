package promptengine.domain.prompt

/**
 * 永続化層からの [Prompt] 復元材料一式（ADR-0006）。[Prompt.restore] にのみ渡す。
 */
data class PromptMemento(
    val key: PromptKey,
    val versions: List<PromptVersionMemento>,
    val rowVersion: Long,
)
