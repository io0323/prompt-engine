package promptengine.domain.prompt

/**
 * PromptVersionのライフサイクル状態（設計書§2.5・§3.5 State パターン）。
 * どの状態からどの操作が許されるかのみを表現する。ガード条件
 * （Validation合格・承認数・依存先Published・参照ゼロ等）の評価は
 * [Prompt] Aggregate側の責務とし、ここでは持たない。
 */
sealed class LifecycleState {
    open fun submitForReview(): LifecycleState = invalidTransition("submitForReview")

    open fun reject(): LifecycleState = invalidTransition("reject")

    open fun withdraw(): LifecycleState = invalidTransition("withdraw")

    open fun approve(): LifecycleState = invalidTransition("approve")

    open fun publish(): LifecycleState = invalidTransition("publish")

    open fun rollback(): LifecycleState = invalidTransition("rollback")

    open fun deprecate(): LifecycleState = invalidTransition("deprecate")

    open fun archive(): LifecycleState = invalidTransition("archive")

    open fun discard(): LifecycleState = invalidTransition("discard")

    protected fun invalidTransition(operation: String): Nothing =
        throw InvalidStateTransitionException(this::class.simpleName ?: "Unknown", operation)

    data object Draft : LifecycleState() {
        override fun submitForReview(): LifecycleState = InReview

        override fun discard(): LifecycleState = Archived
    }

    data object InReview : LifecycleState() {
        override fun reject(): LifecycleState = Draft

        override fun withdraw(): LifecycleState = Draft

        override fun approve(): LifecycleState = Approved
    }

    data object Approved : LifecycleState() {
        override fun publish(): LifecycleState = Published
    }

    data object Published : LifecycleState() {
        override fun rollback(): LifecycleState = Published

        override fun deprecate(): LifecycleState = Deprecated
    }

    data object Deprecated : LifecycleState() {
        override fun archive(): LifecycleState = Archived
    }

    data object Archived : LifecycleState()
}
