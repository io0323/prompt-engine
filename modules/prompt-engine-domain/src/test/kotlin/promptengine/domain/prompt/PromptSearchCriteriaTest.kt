package promptengine.domain.prompt

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.shared.Page

class PromptSearchCriteriaTest {
    @Test
    fun `既定値はq tag category statusがnull page0 sizeはDEFAULT_SIZE`() {
        val criteria = PromptSearchCriteria()

        criteria.q shouldBe null
        criteria.tag shouldBe null
        criteria.category shouldBe null
        criteria.status shouldBe null
        criteria.page shouldBe 0
        criteria.size shouldBe Page.DEFAULT_SIZE
    }

    @Test
    fun `全フィールドを指定できる`() {
        val criteria =
            PromptSearchCriteria(
                q = "faq",
                tag = "customer",
                category = "support",
                status = LifecycleState.Published,
                page = 1,
                size = 10,
            )

        criteria.q shouldBe "faq"
        criteria.tag shouldBe "customer"
        criteria.category shouldBe "support"
        criteria.status shouldBe LifecycleState.Published
        criteria.page shouldBe 1
        criteria.size shouldBe 10
    }
}
