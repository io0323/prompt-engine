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
    open fun publish(): PublicationState = invalidTransition("publish")

    open fun archive(): PublicationState = invalidTransition("archive")

    protected fun invalidTransition(operation: String): Nothing =
        throw InvalidStateTransitionException(this::class.simpleName ?: "Unknown", operation)

    data object Draft : PublicationState() {
        override fun publish(): PublicationState = Published

        override fun archive(): PublicationState = Archived
    }

    data object Published : PublicationState() {
        override fun archive(): PublicationState = Archived
    }

    data object Archived : PublicationState()
}
