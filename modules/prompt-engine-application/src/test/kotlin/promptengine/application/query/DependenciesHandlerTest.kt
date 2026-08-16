package promptengine.application.query

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.dependency.DependencyEdge
import promptengine.domain.dependency.DependencyKind
import promptengine.domain.dependency.DependencyRepository
import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.SemVer

class DependenciesHandlerTest {
    private val promptKey = PromptKey("team/greeting")

    private class FakeDependencyRepository : DependencyRepository {
        override fun findOutbound(promptKey: PromptKey): List<DependencyEdge> =
            listOf(DependencyEdge(promptKey, SemVer(1, 0, 0), DependencyKind.TEMPLATE, "team/base", null))

        override fun findOutbound(
            promptKey: PromptKey,
            semVer: SemVer,
        ): List<DependencyEdge> = emptyList()

        override fun findInbound(promptKey: PromptKey): List<DependencyEdge> =
            listOf(
                DependencyEdge(PromptKey("team/other"), SemVer(1, 0, 0), DependencyKind.PROMPT, promptKey.value, null),
            )

        override fun findInboundTemplateOrFragment(
            kind: DependencyKind,
            key: String,
        ): List<DependencyEdge> = emptyList()

        override fun replaceOutbound(
            promptKey: PromptKey,
            semVer: SemVer,
            edges: List<DependencyEdge>,
        ) = Unit
    }

    @Test
    fun `OUT方向はfindOutboundの結果を返す`() {
        val handler = DependenciesHandler(FakeDependencyRepository())

        val edges = handler.handle(DependenciesQuery(promptKey, DependencyDirection.OUT))

        edges.single().toKey shouldBe "team/base"
    }

    @Test
    fun `IN方向はfindInboundの結果を返す`() {
        val handler = DependenciesHandler(FakeDependencyRepository())

        val edges = handler.handle(DependenciesQuery(promptKey, DependencyDirection.IN))

        edges.single().fromKey shouldBe PromptKey("team/other")
    }
}
