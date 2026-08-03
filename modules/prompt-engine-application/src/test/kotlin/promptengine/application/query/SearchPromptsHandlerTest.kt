package promptengine.application.query

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.prompt.LifecycleState
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.PromptSearchCriteria
import promptengine.domain.prompt.PromptSearchRepository
import promptengine.domain.prompt.PromptSummary
import promptengine.domain.shared.Page

class SearchPromptsHandlerTest {
    private class FakePromptSearchRepository : PromptSearchRepository {
        override fun search(criteria: PromptSearchCriteria): Page<PromptSummary> =
            Page(
                items =
                    listOf(
                        PromptSummary(
                            key = PromptKey("team/greeting"),
                            name = "挨拶",
                            category = null,
                            tags = emptyList(),
                            status = LifecycleState.Draft,
                            latestVersion = "1.0.0",
                            publishedVersion = null,
                        ),
                    ),
                page = criteria.page,
                size = criteria.size,
                totalElements = 1,
            )
    }

    @Test
    fun `PromptSearchRepositoryへ委譲する`() {
        val handler = SearchPromptsHandler(FakePromptSearchRepository())

        val page = handler.handle(SearchPromptsQuery(PromptSearchCriteria(q = "greeting")))

        page.items.single().key shouldBe PromptKey("team/greeting")
    }
}
