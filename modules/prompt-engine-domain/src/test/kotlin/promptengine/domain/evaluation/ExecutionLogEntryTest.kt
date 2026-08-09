package promptengine.domain.evaluation

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.execution.Usage
import promptengine.domain.shared.Cost
import promptengine.domain.shared.LatencyMs
import promptengine.domain.shared.SemVer
import promptengine.domain.shared.TokenCount
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class ExecutionLogEntryTest {
    private fun entry(
        promptKey: String = "support/faq",
        callerSystem: String = "system",
    ) = ExecutionLogEntry(
        eventId = UUID.randomUUID(),
        promptKey = promptKey,
        semVer = SemVer(1, 2, 3),
        callerSystem = callerSystem,
        traceId = "trace-1",
        latency = LatencyMs(120),
        usage = Usage(TokenCount(100), TokenCount(20)),
        cost = Cost(BigDecimal("0.12")),
        status = ExecutionStatus.SUCCESS,
        executedAt = Instant.EPOCH,
    )

    @Test
    fun `promptKeyが空白の場合は例外を投げる`() {
        shouldThrow<IllegalArgumentException> { entry(promptKey = "") }
    }

    @Test
    fun `callerSystemが空白の場合は例外を投げる`() {
        shouldThrow<IllegalArgumentException> { entry(callerSystem = " ") }
    }

    @Test
    fun `全フィールドがそのまま保持される`() {
        val created = entry()

        created.semVer shouldBe SemVer(1, 2, 3)
        created.status shouldBe ExecutionStatus.SUCCESS
        created.cost shouldBe Cost(BigDecimal("0.12"))
        created.latency shouldBe LatencyMs(120)
    }

    @Test
    fun `ExecutionStatusはSUCCESSとFAILEDの2値`() {
        ExecutionStatus.entries.toSet() shouldBe setOf(ExecutionStatus.SUCCESS, ExecutionStatus.FAILED)
    }
}
