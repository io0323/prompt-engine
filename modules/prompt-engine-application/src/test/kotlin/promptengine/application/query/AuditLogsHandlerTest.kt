package promptengine.application.query

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.audit.AuditLogEntry
import promptengine.domain.audit.AuditQuery
import promptengine.domain.audit.AuditRecord
import promptengine.domain.audit.AuditRepository
import promptengine.domain.shared.Page

class AuditLogsHandlerTest {
    private class FakeAuditRepository : AuditRepository {
        override fun append(record: AuditRecord) = Unit

        override fun record(entry: AuditLogEntry) = Unit

        override fun search(query: AuditQuery): Page<AuditLogEntry> = Page(emptyList(), query.page, query.size, 0)
    }

    @Test
    fun `AuditRepositoryへ委譲する`() {
        val handler = AuditLogsHandler(FakeAuditRepository())

        val page = handler.handle(AuditQuery(actor = "tester"))

        page.totalElements shouldBe 0
    }
}
