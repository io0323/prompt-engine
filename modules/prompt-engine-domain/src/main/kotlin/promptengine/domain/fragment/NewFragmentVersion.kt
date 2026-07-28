package promptengine.domain.fragment

import promptengine.domain.shared.SemVer
import promptengine.domain.variable.VariableDefinition

/**
 * [Fragment.create] / [Fragment.newVersion] に渡す、新規Version作成に必要な素材。
 * [FragmentVersion] とは異なり `state` を持たない（[promptengine.domain.template.NewTemplateVersion] と同じ理由）。
 */
data class NewFragmentVersion(
    val semVer: SemVer,
    val content: FragmentContent,
    val variables: List<VariableDefinition> = emptyList(),
)
