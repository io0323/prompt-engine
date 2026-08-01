package promptengine.infrastructure.audit

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.audit.AuditOutcome
import promptengine.domain.audit.AuditRecord
import promptengine.domain.pipeline.PipelineMode
import java.time.Instant

/** [InMemoryAuditRepository]の単体テスト（ADR-0015決定7）。 */
class InMemoryAuditRepositoryTest {
    private fun record(traceId: String) =
        AuditRecord(
            traceId = traceId,
            promptKey = "support/faq",
            mode = PipelineMode.FULL_EXECUTION,
            stageDurationsMs = emptyMap(),
            outcome = AuditOutcome.Success,
            occurredAt = Instant.EPOCH,
        )

    @Test
    fun `production プロファイルでは構築時にIllegalStateExceptionを投げる`() {
        shouldThrow<IllegalStateException> { InMemoryAuditRepository(setOf("production")) }
    }

    @Test
    fun `production 以外のプロファイルでは構築でき appendした記録をsnapshotで取得できる`() {
        val repository = InMemoryAuditRepository(setOf("local"))

        repository.append(record("trace-1"))
        repository.append(record("trace-2"))

        repository.snapshot().map { it.traceId } shouldBe listOf("trace-1", "trace-2")
    }
}
