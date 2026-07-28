package promptengine.domain.template

import promptengine.domain.shared.PublicationState
import promptengine.domain.shared.SemVer
import promptengine.domain.variable.VariableDefinition

/** 永続化層からの [TemplateVersion] 復元材料一式（ADR-0008、PromptVersionMementoと同型）。 */
data class TemplateVersionMemento(
    val semVer: SemVer,
    val content: TemplateContent,
    val variables: List<VariableDefinition> = emptyList(),
    val extendsKey: TemplateKey? = null,
    val state: PublicationState,
)
