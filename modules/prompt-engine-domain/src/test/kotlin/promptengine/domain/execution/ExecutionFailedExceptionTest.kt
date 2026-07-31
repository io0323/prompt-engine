package promptengine.domain.execution

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ExecutionFailedExceptionTest {
    @Test
    fun `errorType retryCount を保持しメッセージにEXECUTION_FAILEDを含む`() {
        val exception = ExecutionFailedException(ExecutionErrorType.SERVER_ERROR, retryCount = 2)

        exception.errorType shouldBe ExecutionErrorType.SERVER_ERROR
        exception.retryCount shouldBe 2
        exception.message shouldBe "EXECUTION_FAILED: errorType=SERVER_ERROR retryCount=2"
    }

    @Test
    fun `causeを保持する`() {
        val cause = RuntimeException("connection reset")

        val exception = ExecutionFailedException(ExecutionErrorType.CONNECTION_FAILURE, retryCount = 0, cause = cause)

        exception.cause shouldBe cause
    }
}
