package promptengine.domain.pipeline

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class InvalidPipelineRequestExceptionTest {
    @Test
    fun `messageをそのまま保持する`() {
        val exception = InvalidPipelineRequestException("executionPolicy is required")

        exception.message shouldBe "executionPolicy is required"
    }
}
