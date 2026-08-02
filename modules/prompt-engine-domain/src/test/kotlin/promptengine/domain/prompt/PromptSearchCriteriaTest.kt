package promptengine.domain.prompt

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.shared.Page

class PromptSearchCriteriaTest {
    @Test
    fun `pageが負の場合は例外を投げる`() {
        shouldThrow<IllegalArgumentException> { PromptSearchCriteria(page = -1) }
    }

    @Test
    fun `sizeが0以下または上限超過の場合は例外を投げる`() {
        shouldThrow<IllegalArgumentException> { PromptSearchCriteria(size = 0) }
        shouldThrow<IllegalArgumentException> { PromptSearchCriteria(size = Page.MAX_SIZE + 1) }
    }

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
