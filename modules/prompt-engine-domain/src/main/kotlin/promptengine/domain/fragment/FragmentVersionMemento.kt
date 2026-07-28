package promptengine.domain.fragment

import promptengine.domain.shared.PublicationState
import promptengine.domain.shared.SemVer
import promptengine.domain.variable.VariableDefinition

/** 永続化層からの [FragmentVersion] 復元材料一式（ADR-0008、PromptVersionMementoと同型）。 */
data class FragmentVersionMemento(
    val semVer: SemVer,
    val content: FragmentContent,
    val variables: List<VariableDefinition> = emptyList(),
    val state: PublicationState,
)
