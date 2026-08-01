package promptengine.domain.render

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** [RenderFailedException]の契約テスト（設計書§13.3 `RENDER_ERROR`、ADR-0015決定4・決定5）。 */
class RenderFailedExceptionTest {
    @Test
    fun `reason を保持しメッセージにRENDER_ERRORを含む`() {
        val exception = RenderFailedException("no OutputFormatter registered for outputFormat=JSON")

        exception.reason shouldBe "no OutputFormatter registered for outputFormat=JSON"
        exception.message shouldBe "RENDER_ERROR: no OutputFormatter registered for outputFormat=JSON"
        exception.cause shouldBe null
    }

    @Test
    fun `causeを指定できる`() {
        val cause = IllegalStateException("template engine crashed")

        val exception = RenderFailedException("template expansion failed", cause)

        exception.cause shouldBe cause
    }
}
