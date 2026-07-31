package promptengine.domain.execution

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.parsing.ParsedOutput
import promptengine.domain.render.OutputFormat
import promptengine.domain.shared.LatencyMs
import promptengine.domain.shared.TokenCount

class ExecutionOutcomeTest {
    private val usage = Usage(TokenCount(1), TokenCount(1))
    private val parsedOutput = ParsedOutput(OutputFormat.TEXT, raw = "ok")

    @Test
    fun `attemptsが空だとIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> { ExecutionOutcome(parsedOutput, emptyList()) }
    }

    @Test
    fun `attemptsは呼出元のMutableListの後続変更から隔離される`() {
        val mutableAttempts = mutableListOf(RawResponse("ok", usage, LatencyMs(10)))

        val outcome = ExecutionOutcome(parsedOutput, mutableAttempts)
        mutableAttempts.add(RawResponse("second", usage, LatencyMs(10)))

        outcome.attempts.size shouldBe 1
    }
}
