package promptengine.domain.observability

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MetricsRecorderTest {
    @Test
    fun `OutcomeはSUCCESSとFAILUREの2種`() {
        Outcome.entries.toSet() shouldBe setOf(Outcome.SUCCESS, Outcome.FAILURE)
    }

    @Test
    fun `TokenDirectionはINPUTとOUTPUTの2種`() {
        TokenDirection.entries.toSet() shouldBe setOf(TokenDirection.INPUT, TokenDirection.OUTPUT)
    }
}
