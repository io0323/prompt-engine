package promptengine.domain.prompt

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PromptSummaryTest {
    @Test
    fun `key name category tags status latestVersion publishedVersionを保持する`() {
        val summary =
            PromptSummary(
                key = PromptKey("support/faq"),
                name = "FAQ回答生成",
                category = "support",
                tags = listOf("faq"),
                status = LifecycleState.Published,
                latestVersion = "1.1.0",
                publishedVersion = "1.0.0",
            )

        summary.key shouldBe PromptKey("support/faq")
        summary.name shouldBe "FAQ回答生成"
        summary.category shouldBe "support"
        summary.tags shouldBe listOf("faq")
        summary.status shouldBe LifecycleState.Published
        summary.latestVersion shouldBe "1.1.0"
        summary.publishedVersion shouldBe "1.0.0"
    }

    @Test
    fun `category publishedVersionはnullを許容する`() {
        val summary =
            PromptSummary(
                key = PromptKey("support/faq"),
                name = "FAQ回答生成",
                category = null,
                tags = emptyList(),
                status = LifecycleState.Draft,
                latestVersion = "0.1.0",
                publishedVersion = null,
            )

        summary.category shouldBe null
        summary.publishedVersion shouldBe null
    }
}
