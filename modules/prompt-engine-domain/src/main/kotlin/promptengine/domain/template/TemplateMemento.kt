package promptengine.domain.template

/** 永続化層からの [Template] 復元材料一式（ADR-0008）。[Template.restore] にのみ渡す。 */
data class TemplateMemento(
    val key: TemplateKey,
    val versions: List<TemplateVersionMemento>,
    val rowVersion: Long,
)
