package promptengine.domain.shared

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PageTest {
    @Test
    fun `items page size totalElementsを保持する`() {
        val page = Page(items = listOf("a", "b"), page = 0, size = 20, totalElements = 2L)

        page.items shouldBe listOf("a", "b")
        page.page shouldBe 0
        page.size shouldBe 20
        page.totalElements shouldBe 2L
    }

    @Test
    fun `pageが負ならIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> { Page(emptyList<String>(), page = -1, size = 20, totalElements = 0) }
    }

    @Test
    fun `sizeが0以下ならIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> { Page(emptyList<String>(), page = 0, size = 0, totalElements = 0) }
    }

    @Test
    fun `sizeが上限を超えるとIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> {
            Page(emptyList<String>(), page = 0, size = Page.MAX_SIZE + 1, totalElements = 0)
        }
    }

    @Test
    fun `totalElementsが負ならIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> { Page(emptyList<String>(), page = 0, size = 20, totalElements = -1) }
    }

    @Test
    fun `DEFAULT_SIZEは20 MAX_SIZEは100`() {
        Page.DEFAULT_SIZE shouldBe 20
        Page.MAX_SIZE shouldBe 100
    }
}
