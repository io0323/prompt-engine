package promptengine.domain.evaluation

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.shared.SemVer
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class EvaluationRecordTest {
    private fun record(
        promptKey: String = "support/faq",
        metricType: String = "Latency",
        method: String = "measured",
    ) = EvaluationRecord(
        eventId = UUID.randomUUID(),
        promptKey = promptKey,
        semVer = SemVer(1, 0, 0),
        metricType = metricType,
        score = BigDecimal("120"),
        method = method,
        sampleRef = null,
        evaluatedAt = Instant.EPOCH,
    )

    @Test
    fun `promptKeyが空白の場合は例外を投げる`() {
        shouldThrow<IllegalArgumentException> { record(promptKey = " ") }
    }

    @Test
    fun `metricTypeが空白の場合は例外を投げる`() {
        shouldThrow<IllegalArgumentException> { record(metricType = "") }
    }

    @Test
    fun `methodが空白の場合は例外を投げる`() {
        shouldThrow<IllegalArgumentException> { record(method = " ") }
    }

    @Test
    fun `sampleRefは省略可能でnullのまま保持される`() {
        record().sampleRef shouldBe null
    }
}
