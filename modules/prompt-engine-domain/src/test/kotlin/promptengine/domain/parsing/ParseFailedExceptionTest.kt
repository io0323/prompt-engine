package promptengine.domain.parsing

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.render.OutputFormat

class ParseFailedExceptionTest {
    @Test
    fun `format reason repairAttempts を保持しメッセージにPARSE_FAILEDを含む`() {
        val exception = ParseFailedException(OutputFormat.JSON, "missing required field: answer", repairAttempts = 1)

        exception.format shouldBe OutputFormat.JSON
        exception.reason shouldBe "missing required field: answer"
        exception.repairAttempts shouldBe 1
        exception.message shouldBe
            "PARSE_FAILED: missing required field: answer (format=JSON, repairAttempts=1)"
    }

    @Test
    fun `repairAttemptsの既定値は0`() {
        ParseFailedException(OutputFormat.JSON, "invalid JSON syntax").repairAttempts shouldBe 0
    }
}
