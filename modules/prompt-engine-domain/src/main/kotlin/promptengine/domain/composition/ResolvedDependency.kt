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
    /** DSL上の範囲指定（`^2`/`1.3.0`/範囲指定なし）。監査・デバッグ用に保持し、解決には使わない。 */
    val requestedRange: VersionRange

    /** [requestedRange] から選ばれた具体的なVersion（決定性のために常にピン留めする）。 */
    val resolvedVersion: SemVer

    /** 解決時点の[resolvedVersion]のライフサイクル状態。Validation Engine（P5）が再照会せず参照する。 */
    val status: PublicationState

    /** [resolvedVersion]の内容のSHA-256ハッシュ（`TemplateContent`/`FragmentContent`と同じ計算方式）。 */
    val contentHash: String

    /** extends（Template extends Template）／Template側importで解決されたTemplateへの依存1件。 */
    data class TemplateDependency(
        /** 参照先Templateの識別子。 */
        val key: TemplateKey,
        override val requestedRange: VersionRange,
        override val resolvedVersion: SemVer,
        override val status: PublicationState,
        override val contentHash: String,
    ) : ResolvedDependency

    /** import／includeで解決されたFragmentへの依存1件。 */
    data class FragmentDependency(
        /** 参照先Fragmentの識別子。 */
        val key: FragmentKey,
        override val requestedRange: VersionRange,
        override val resolvedVersion: SemVer,
        override val status: PublicationState,
        override val contentHash: String,
    ) : ResolvedDependency
}
