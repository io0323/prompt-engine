package promptengine.domain.composition

import promptengine.domain.fragment.FragmentKey
import promptengine.domain.shared.PublicationState
import promptengine.domain.shared.SemVer
import promptengine.domain.shared.VersionRange
import promptengine.domain.template.TemplateKey

/**
 * extends/import/includeで参照され、解決済みの1依存（設計書§4.5 CompiledPrompt生成、
 * ADR-0009）。[requestedRange] はDSL上の範囲指定（監査・デバッグ用）、[resolvedVersion] は
 * その範囲から選ばれた具体的なVersion（決定性のために常にピン留めする）。
 */
sealed interface ResolvedDependency {
    val requestedRange: VersionRange
    val resolvedVersion: SemVer
    val status: PublicationState
    val contentHash: String

    data class TemplateDependency(
        val key: TemplateKey,
        override val requestedRange: VersionRange,
        override val resolvedVersion: SemVer,
        override val status: PublicationState,
        override val contentHash: String,
    ) : ResolvedDependency

    data class FragmentDependency(
        val key: FragmentKey,
        override val requestedRange: VersionRange,
        override val resolvedVersion: SemVer,
        override val status: PublicationState,
        override val contentHash: String,
    ) : ResolvedDependency
}
