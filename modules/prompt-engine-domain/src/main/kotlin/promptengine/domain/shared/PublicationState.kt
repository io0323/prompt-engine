package promptengine.domain.shared

/**
 * Template/Fragment共通の簡略ライフサイクル（設計書§4.3、ADR-0008）。
 *
 * Promptの6状態（[promptengine.domain.prompt.LifecycleState]、Review/Approval付き）
 * とは意図的に別クラス。§4.3にTemplate/Fragmentの承認フローに関する不変条件が
 * 存在せず、§4.5のDomain Service一覧にも承認関連のServiceが無いため、
 * Review/Approved相当の状態を導入する設計書上の根拠が無い（ADR-0008）。
 * Deprecated相当の状態も持たない。§2.10のDependencyValidationも
 * 「Published以外の参照を拒否」とだけ定めており、Deprecated/Archivedを区別する
 * 必要が無いため。
 */
sealed class PublicationState {
    /** Draft→Published。Draft以外から呼ぶと [InvalidStateTransitionException] を投げる。 */
    open fun publish(): PublicationState = invalidTransition("publish")

    /** Draft/Published→Archived。Archived状態で呼ぶと [InvalidStateTransitionException] を投げる。 */
    open fun archive(): PublicationState = invalidTransition("archive")

    protected fun invalidTransition(operation: String): Nothing =
        throw InvalidStateTransitionException(this::class.simpleName ?: "Unknown", operation)

    /** 新規作成直後の状態。`publish`/`archive` のいずれも呼び出し可能。 */
    data object Draft : PublicationState() {
        override fun publish(): PublicationState = Published

        override fun archive(): PublicationState = Archived
    }

    /** 公開済みの状態。`archive` のみ呼び出し可能。 */
    data object Published : PublicationState() {
        override fun archive(): PublicationState = Archived
    }

    /** 終端状態。以降どの操作も呼び出せない。 */
    data object Archived : PublicationState()
}
