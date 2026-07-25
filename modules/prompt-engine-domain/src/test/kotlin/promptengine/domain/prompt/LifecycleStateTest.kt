package promptengine.domain.prompt

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * LifecycleState（State パターン）単体のテスト。
 * §2.5 遷移表のうち「どの状態からどの操作が許されるか」はここで検証し、
 * ガード条件（Validation合格・承認数・依存先Published・参照ゼロ）の検証は
 * Prompt Aggregate 側（PromptTest）の責務とする。
 */
class LifecycleStateTest {
    @Test
    fun `Draft は submitForReview でInReviewへ遷移する`() {
        LifecycleState.Draft.submitForReview() shouldBe LifecycleState.InReview
    }

    @Test
    fun `Draft は discard でArchivedへ遷移する`() {
        LifecycleState.Draft.discard() shouldBe LifecycleState.Archived
    }

    @Test
    fun `Draft で approve を呼ぶとInvalidStateTransitionExceptionを投げる`() {
        shouldThrow<InvalidStateTransitionException> { LifecycleState.Draft.approve() }
    }

    @Test
    fun `InReview は reject でDraftへ遷移する`() {
        LifecycleState.InReview.reject() shouldBe LifecycleState.Draft
    }

    @Test
    fun `InReview は withdraw でDraftへ遷移する`() {
        LifecycleState.InReview.withdraw() shouldBe LifecycleState.Draft
    }

    @Test
    fun `InReview は approve でApprovedへ遷移する`() {
        LifecycleState.InReview.approve() shouldBe LifecycleState.Approved
    }

    @Test
    fun `InReview で publish を呼ぶとInvalidStateTransitionExceptionを投げる`() {
        shouldThrow<InvalidStateTransitionException> { LifecycleState.InReview.publish() }
    }

    @Test
    fun `Approved は publish でPublishedへ遷移する`() {
        LifecycleState.Approved.publish() shouldBe LifecycleState.Published
    }

    @Test
    fun `Approved で submitForReview を呼ぶとInvalidStateTransitionExceptionを投げる`() {
        shouldThrow<InvalidStateTransitionException> { LifecycleState.Approved.submitForReview() }
    }

    @Test
    fun `Published は rollback で自身と同じPublishedへ遷移する`() {
        LifecycleState.Published.rollback() shouldBe LifecycleState.Published
    }

    @Test
    fun `Published は deprecate でDeprecatedへ遷移する`() {
        LifecycleState.Published.deprecate() shouldBe LifecycleState.Deprecated
    }

    @Test
    fun `Published で discard を呼ぶとInvalidStateTransitionExceptionを投げる`() {
        shouldThrow<InvalidStateTransitionException> { LifecycleState.Published.discard() }
    }

    @Test
    fun `Deprecated は archive でArchivedへ遷移する`() {
        LifecycleState.Deprecated.archive() shouldBe LifecycleState.Archived
    }

    @Test
    fun `Deprecated で publish を呼ぶとInvalidStateTransitionExceptionを投げる`() {
        shouldThrow<InvalidStateTransitionException> { LifecycleState.Deprecated.publish() }
    }

    @Test
    fun `Archived はどの操作を呼んでもInvalidStateTransitionExceptionを投げる 終端状態`() {
        shouldThrow<InvalidStateTransitionException> { LifecycleState.Archived.submitForReview() }
        shouldThrow<InvalidStateTransitionException> { LifecycleState.Archived.discard() }
        shouldThrow<InvalidStateTransitionException> { LifecycleState.Archived.archive() }
    }
}
