package promptengine.domain.audit

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.shared.Page
import java.time.Instant

class AuditQueryTest {
    @Test
    fun `既定値はaggregateId actor from toがnull page0 sizeはDEFAULT_SIZE`() {
        val query = AuditQuery()

        query.aggregateId shouldBe null
        query.actor shouldBe null
        query.from shouldBe null
        query.to shouldBe null
        query.page shouldBe 0
        query.size shouldBe Page.DEFAULT_SIZE
    }

    @Test
    fun `全フィールドを指定できる`() {
        val from = Instant.EPOCH
        val to = Instant.EPOCH.plusSeconds(60)

        val query = AuditQuery(aggregateId = "support/faq", actor = "user:a", from = from, to = to, page = 2, size = 10)

        query.aggregateId shouldBe "support/faq"
        query.actor shouldBe "user:a"
        query.from shouldBe from
        query.to shouldBe to
        query.page shouldBe 2
        query.size shouldBe 10
    }
}
