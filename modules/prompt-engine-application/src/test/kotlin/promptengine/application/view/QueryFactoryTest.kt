package promptengine.application.view

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.application.query.DependencyDirection
import promptengine.domain.prompt.PromptKey
import java.time.Instant

class QueryFactoryTest {
    @Test
    fun `dependenciesQuery はkeyと小文字directionを変換する`() {
        val query = QueryFactory.dependenciesQuery("support/faq-answer", "in")

        query.key shouldBe PromptKey("support/faq-answer")
        query.direction shouldBe DependencyDirection.IN
    }

    @Test
    fun `dependenciesQuery は大文字directionもそのまま解釈する`() {
        QueryFactory.dependenciesQuery("support/faq-answer", "OUT").direction shouldBe DependencyDirection.OUT
    }

    @Test
    fun `dependenciesQuery は未知のdirectionでIllegalArgumentException`() {
        shouldThrow<IllegalArgumentException> {
            QueryFactory.dependenciesQuery("support/faq-answer", "sideways")
        }
    }

    @Test
    fun `metricsQuery はkey-from-toを変換する`() {
        val from = Instant.parse("2026-01-01T00:00:00Z")
        val to = Instant.parse("2026-01-02T00:00:00Z")

        val query = QueryFactory.metricsQuery("support/faq-answer", from, to)

        query.key shouldBe PromptKey("support/faq-answer")
        query.from shouldBe from
        query.to shouldBe to
    }
}
