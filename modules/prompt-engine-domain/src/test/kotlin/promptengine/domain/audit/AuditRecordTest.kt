package promptengine.domain.audit

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import promptengine.domain.pipeline.PipelineMode
import java.time.Instant

/** [AuditRecord]の契約テスト（設計書§2.6ステージ12、ADR-0015決定7）。 */
class AuditRecordTest {
    @Test
    fun `フィールドをそのまま保持する`() {
        val record =
            AuditRecord(
                traceId = "trace-1",
                promptKey = "support/faq",
                mode = PipelineMode.FULL_EXECUTION,
                stageDurationsMs = mapOf("Load" to 3L),
                outcome = AuditOutcome.Success,
                occurredAt = Instant.EPOCH,
            )

        record.traceId shouldBe "trace-1"
        record.promptKey shouldBe "support/faq"
        record.mode shouldBe PipelineMode.FULL_EXECUTION
        record.stageDurationsMs shouldBe mapOf("Load" to 3L)
        record.outcome shouldBe AuditOutcome.Success
    }

    @Test
    fun `promptKeyはnullを許容する`() {
        val record =
            AuditRecord(
                traceId = "trace-1",
                promptKey = null,
                mode = PipelineMode.RENDER_ONLY,
                stageDurationsMs = emptyMap(),
                outcome = AuditOutcome.Failure("PROMPT_NOT_FOUND"),
                occurredAt = Instant.EPOCH,
            )

        record.promptKey shouldBe null
    }

    @Test
    fun `outcomeが異なれば等価ではない`() {
        val base =
            AuditRecord(
                traceId = "trace-1",
                promptKey = "support/faq",
                mode = PipelineMode.FULL_EXECUTION,
                stageDurationsMs = emptyMap(),
                outcome = AuditOutcome.Success,
                occurredAt = Instant.EPOCH,
            )

        val failed = base.copy(outcome = AuditOutcome.Failure("VALIDATION_FAILED"))

        failed shouldNotBe base
    }
}
