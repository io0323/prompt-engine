package promptengine.domain.fragment

import promptengine.domain.shared.PersistenceApi
import promptengine.domain.shared.SemVer

/**
 * Prompt Authoringコンテキストの Aggregate Root（設計書§4.3）。
 *
 * 不変条件:
 * - `versions` は1件以上（[init] ブロックで検証）
 * - 循環Include禁止: 本AggregateはInclude先を構造化フィールドとして持たないため
 *   （[FragmentVersion] のKDoc参照）、自己Includeを含め循環検出を一切行わない。
 *   全て3c（CompositionService）に委ねる（ADR-0008）。
 *
 * Promptと異なり、ライフサイクルは簡略版3状態（[promptengine.domain.shared.PublicationState]）
 * であり、Domain Eventも本フェーズでは発行しない（ADR-0008）。
 *
 * プライマリコンストラクタは `internal`（[ConsistentCopyVisibility] により `copy()` も
 * 同様に `internal`）。新規作成は [create]、永続化層からの復元は [restore] を使うこと。
 */
@ConsistentCopyVisibility
data class Fragment internal constructor(
    val key: FragmentKey,
    val versions: List<FragmentVersion>,
    val rowVersion: Long = 0,
) {
    init {
        require(versions.isNotEmpty()) { "Fragment must have at least one version" }
    }

    private fun version(semVer: SemVer): FragmentVersion =
        versions.find { it.semVer == semVer } ?: throw FragmentVersionNotFoundException(semVer)

    private fun replaceVersion(
        semVer: SemVer,
        transform: (FragmentVersion) -> FragmentVersion,
    ): Fragment = copy(versions = versions.map { if (it.semVer == semVer) transform(it) else it })

    companion object {
        /** (新規)→Draft。新規Fragmentを最初のVersion（Draft状態）とともに作成する。 */
        fun create(
            key: FragmentKey,
            version: NewFragmentVersion,
        ): Fragment {
            val fragmentVersion = FragmentVersion(version.semVer, version.content, version.variables)
            return Fragment(key, listOf(fragmentVersion))
        }

        /**
         * 永続化層からの復元専用（ADR-0006、ADR-0008）。DBの行自体が過去の正当な
         * 遷移列の結果であることを信頼し、遷移の正当性は検証しない。
         */
        @PersistenceApi
        fun restore(memento: FragmentMemento): Fragment {
            val versions =
                memento.versions.map {
                    FragmentVersion(it.semVer, it.content, it.variables, it.state)
                }
            return Fragment(memento.key, versions, memento.rowVersion)
        }
    }

    /** (新規)→Draft。既存Fragmentに新しいDraft Versionを追加する。 */
    fun newVersion(version: NewFragmentVersion): Fragment {
        require(versions.none { it.semVer == version.semVer }) { "version ${version.semVer} already exists" }
        val fragmentVersion = FragmentVersion(version.semVer, version.content, version.variables)
        return copy(versions = versions + fragmentVersion)
    }

    /** Draft→Published。 */
    fun publish(semVer: SemVer): Fragment {
        val newState = version(semVer).state.publish()
        return replaceVersion(semVer) { it.copy(state = newState) }
    }

    /** Draft/Published→Archived。 */
    fun archive(semVer: SemVer): Fragment {
        val newState = version(semVer).state.archive()
        return replaceVersion(semVer) { it.copy(state = newState) }
    }
}
