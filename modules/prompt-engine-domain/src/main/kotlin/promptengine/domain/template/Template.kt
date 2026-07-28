package promptengine.domain.template

import promptengine.domain.shared.PersistenceApi
import promptengine.domain.shared.SemVer

/**
 * Prompt Authoringコンテキストの Aggregate Root（設計書§4.3）。
 *
 * 不変条件:
 * - `versions` は1件以上（[init] ブロックで検証）
 * - 循環継承禁止・自己参照不可（`extends鎖に自身不可`）: 本Aggregateが検出できるのは
 *   直接の自己参照（`extendsKey == 自分自身のkey`）のみ。`A extends B extends A` の
 *   ような長さ2以上の循環は他Aggregateを辿るDBアクセスが必要でありAggregate単体では
 *   検出不可能なため、3c（CompositionService、DFS）に委ねる（ADR-0008）。
 *
 * Promptと異なり、ライフサイクルは簡略版3状態（[promptengine.domain.shared.PublicationState]）
 * であり、Domain Eventも本フェーズでは発行しない（§14に対応イベントが定義されていないため。
 * ADR-0008）。
 *
 * プライマリコンストラクタは `internal`（[ConsistentCopyVisibility] により `copy()` も
 * 同様に `internal`）。新規作成は [create]、永続化層からの復元は [restore] を使うこと。
 */
@ConsistentCopyVisibility
data class Template internal constructor(
    val key: TemplateKey,
    val versions: List<TemplateVersion>,
    val rowVersion: Long = 0,
) {
    init {
        require(versions.isNotEmpty()) { "Template must have at least one version" }
        require(versions.none { it.extendsKey == key }) {
            "Template must not extend itself: ${key.value}"
        }
    }

    private fun version(semVer: SemVer): TemplateVersion =
        versions.find { it.semVer == semVer } ?: throw TemplateVersionNotFoundException(semVer)

    private fun replaceVersion(
        semVer: SemVer,
        transform: (TemplateVersion) -> TemplateVersion,
    ): Template = copy(versions = versions.map { if (it.semVer == semVer) transform(it) else it })

    companion object {
        /** (新規)→Draft。新規Templateを最初のVersion（Draft状態）とともに作成する。 */
        fun create(
            key: TemplateKey,
            version: NewTemplateVersion,
        ): Template {
            val templateVersion =
                TemplateVersion(version.semVer, version.content, version.variables, version.extendsKey)
            return Template(key, listOf(templateVersion))
        }

        /**
         * 永続化層からの復元専用（ADR-0006、ADR-0008）。DBの行自体が過去の正当な
         * 遷移列の結果であることを信頼し、遷移の正当性は検証しない。「自己参照不可」の
         * 不変条件のみ [init] が引き続き検証する。
         */
        @PersistenceApi
        fun restore(memento: TemplateMemento): Template {
            val versions =
                memento.versions.map {
                    TemplateVersion(it.semVer, it.content, it.variables, it.extendsKey, it.state)
                }
            return Template(memento.key, versions, memento.rowVersion)
        }
    }

    /** (新規)→Draft。既存Templateに新しいDraft Versionを追加する。 */
    fun newVersion(version: NewTemplateVersion): Template {
        require(versions.none { it.semVer == version.semVer }) { "version ${version.semVer} already exists" }
        val templateVersion =
            TemplateVersion(version.semVer, version.content, version.variables, version.extendsKey)
        return copy(versions = versions + templateVersion)
    }

    /** Draft→Published。 */
    fun publish(semVer: SemVer): Template {
        val newState = version(semVer).state.publish()
        return replaceVersion(semVer) { it.copy(state = newState) }
    }

    /** Draft/Published→Archived。 */
    fun archive(semVer: SemVer): Template {
        val newState = version(semVer).state.archive()
        return replaceVersion(semVer) { it.copy(state = newState) }
    }
}
