package promptengine.domain.dependency

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.SemVer

class DependencyEdgeTest {
    @Test
    fun `fromKey fromVersion toKind toKey toVersionを保持する`() {
        val edge =
            DependencyEdge(
                fromKey = PromptKey("support/faq"),
                fromVersion = SemVer(1, 0, 0),
                toKind = DependencyKind.TEMPLATE,
                toKey = "templates/base",
                toVersion = "^1",
            )

        edge.fromKey shouldBe PromptKey("support/faq")
        edge.fromVersion shouldBe SemVer(1, 0, 0)
        edge.toKind shouldBe DependencyKind.TEMPLATE
        edge.toKey shouldBe "templates/base"
        edge.toVersion shouldBe "^1"
    }

    @Test
    fun `toVersionはnullを許容する`() {
        val edge =
            DependencyEdge(PromptKey("support/faq"), SemVer(1, 0, 0), DependencyKind.PROMPT, "support/other", null)

        edge.toVersion shouldBe null
    }

    @Test
    fun `DependencyKindはTEMPLATE FRAGMENT PROMPTの3種`() {
        val expected = setOf(DependencyKind.TEMPLATE, DependencyKind.FRAGMENT, DependencyKind.PROMPT)

        DependencyKind.entries.toSet() shouldBe expected
    }
}
