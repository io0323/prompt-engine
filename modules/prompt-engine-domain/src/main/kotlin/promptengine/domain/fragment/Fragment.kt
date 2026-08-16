package promptengine.domain.fragment

import promptengine.domain.event.EventContext
import promptengine.domain.shared.PersistenceApi
import promptengine.domain.shared.SemVer
import java.util.UUID

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
 * であり、レビューワークフローに対応するイベントは持たない。M2-3（Issue #15、ADR-0033）で
 * `FragmentCreated`/`FragmentVersionCreated`/`FragmentPublished`/`FragmentArchived`の
 * 4イベントを発行するようになった（[promptengine.domain.template.Template]と同じ理由）。
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
            context: EventContext,
        ): Pair<Fragment, FragmentCreated> {
            val fragmentVersion = FragmentVersion(version.semVer, version.content, version.variables)
            val fragment = Fragment(key, listOf(fragmentVersion))
            val event =
                FragmentCreated(
                    eventId = UUID.randomUUID(),
                    occurredAt = context.occurredAt,
                    aggregateId = key.value,
                    actor = context.actor,
                    traceId = context.traceId,
                    payload = FragmentCreated.Payload(key.value, version.semVer),
                )
            return fragment to event
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
    fun newVersion(
        version: NewFragmentVersion,
        context: EventContext,
    ): Pair<Fragment, FragmentVersionCreated> {
        require(versions.none { it.semVer == version.semVer }) { "version ${version.semVer} already exists" }
        val fragmentVersion = FragmentVersion(version.semVer, version.content, version.variables)
        val fragment = copy(versions = versions + fragmentVersion)
        val event =
            FragmentVersionCreated(
                eventId = UUID.randomUUID(),
                occurredAt = context.occurredAt,
                aggregateId = key.value,
                actor = context.actor,
                traceId = context.traceId,
                payload = FragmentVersionCreated.Payload(key.value, version.semVer),
            )
        return fragment to event
    }

    /** Draft→Published。 */
    fun publish(
        semVer: SemVer,
        context: EventContext,
    ): Pair<Fragment, FragmentPublished> {
        val newState = version(semVer).state.publish()
        val fragment = replaceVersion(semVer) { it.copy(state = newState) }
        val event =
            FragmentPublished(
                eventId = UUID.randomUUID(),
                occurredAt = context.occurredAt,
                aggregateId = key.value,
                actor = context.actor,
                traceId = context.traceId,
                payload = FragmentPublished.Payload(key.value, semVer),
            )
        return fragment to event
    }

    /** Draft/Published→Archived。 */
    fun archive(
        semVer: SemVer,
        context: EventContext,
    ): Pair<Fragment, FragmentArchived> {
        val newState = version(semVer).state.archive()
        val fragment = replaceVersion(semVer) { it.copy(state = newState) }
        val event =
            FragmentArchived(
                eventId = UUID.randomUUID(),
                occurredAt = context.occurredAt,
                aggregateId = key.value,
                actor = context.actor,
                traceId = context.traceId,
                payload = FragmentArchived.Payload(key.value, semVer),
            )
        return fragment to event
    }
}
