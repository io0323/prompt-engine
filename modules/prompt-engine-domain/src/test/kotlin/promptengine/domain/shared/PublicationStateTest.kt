package promptengine.domain.shared

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * PublicationState（Template/Fragment共通の簡略State パターン）単体のテスト（ADR-0008）。
 */
class PublicationStateTest {
    @Test
    fun `Draft は publish でPublishedへ遷移する`() {
        PublicationState.Draft.publish() shouldBe PublicationState.Published
    }

    @Test
    fun `Draft は archive でArchivedへ遷移する`() {
        PublicationState.Draft.archive() shouldBe PublicationState.Archived
    }

    @Test
    fun `Published は archive でArchivedへ遷移する`() {
        PublicationState.Published.archive() shouldBe PublicationState.Archived
    }

    @Test
    fun `Published で publish を呼ぶとInvalidStateTransitionExceptionを投げる`() {
        shouldThrow<InvalidStateTransitionException> { PublicationState.Published.publish() }
    }

    @Test
    fun `Archived はどの操作を呼んでもInvalidStateTransitionExceptionを投げる 終端状態`() {
        shouldThrow<InvalidStateTransitionException> { PublicationState.Archived.publish() }
        shouldThrow<InvalidStateTransitionException> { PublicationState.Archived.archive() }
    }
}
