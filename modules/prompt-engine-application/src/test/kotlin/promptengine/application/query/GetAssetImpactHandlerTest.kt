package promptengine.application.query

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.application.command.InMemoryDependencyRepository
import promptengine.domain.dependency.DependencyEdge
import promptengine.domain.dependency.DependencyKind
import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.SemVer

class GetAssetImpactHandlerTest {
    private val handler = GetAssetImpactHandler(InMemoryDependencyRepository())

    @Test
    fun `指定Fragmentに依存するPromptのキー一覧を返す`() {
        val repo = InMemoryDependencyRepository()
        repo.replaceOutbound(
            PromptKey("team/greeting"),
            SemVer(1, 0, 0),
            listOf(
                DependencyEdge(
                    PromptKey("team/greeting"),
                    SemVer(1, 0, 0),
                    DependencyKind.FRAGMENT,
                    "shared/disclaimer",
                    null,
                ),
            ),
        )
        repo.replaceOutbound(
            PromptKey("team/farewell"),
            SemVer(2, 0, 0),
            listOf(
                DependencyEdge(
                    PromptKey("team/farewell"),
                    SemVer(2, 0, 0),
                    DependencyKind.FRAGMENT,
                    "shared/disclaimer",
                    null,
                ),
            ),
        )
        val result = GetAssetImpactHandler(repo).handle(AssetImpactQuery(DependencyKind.FRAGMENT, "shared/disclaimer"))

        result shouldContainExactlyInAnyOrder listOf(PromptKey("team/greeting"), PromptKey("team/farewell"))
    }

    @Test
    fun `指定Templateに依存するPromptのキー一覧を返す`() {
        val repo = InMemoryDependencyRepository()
        repo.replaceOutbound(
            PromptKey("support/faq"),
            SemVer(1, 0, 0),
            listOf(
                DependencyEdge(PromptKey("support/faq"), SemVer(1, 0, 0), DependencyKind.TEMPLATE, "base/chat", null),
            ),
        )
        val result = GetAssetImpactHandler(repo).handle(AssetImpactQuery(DependencyKind.TEMPLATE, "base/chat"))

        result.single() shouldBe PromptKey("support/faq")
    }

    @Test
    fun `同一Promptが複数Versionで同じFragmentに依存していても重複なく返す`() {
        val repo = InMemoryDependencyRepository()
        val promptKey = PromptKey("team/multi")
        repo.replaceOutbound(
            promptKey,
            SemVer(1, 0, 0),
            listOf(DependencyEdge(promptKey, SemVer(1, 0, 0), DependencyKind.FRAGMENT, "shared/header", null)),
        )
        repo.replaceOutbound(
            promptKey,
            SemVer(2, 0, 0),
            listOf(DependencyEdge(promptKey, SemVer(2, 0, 0), DependencyKind.FRAGMENT, "shared/header", null)),
        )
        val result = GetAssetImpactHandler(repo).handle(AssetImpactQuery(DependencyKind.FRAGMENT, "shared/header"))

        result shouldHaveSize 1
        result.single() shouldBe promptKey
    }

    @Test
    fun `依存するPromptが存在しない場合は空リストを返す`() {
        val result = handler.handle(AssetImpactQuery(DependencyKind.FRAGMENT, "shared/nonexistent"))

        result shouldBe emptyList()
    }

    @Test
    fun `kindにPROMPTを指定するとIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> {
            handler.handle(AssetImpactQuery(DependencyKind.PROMPT, "team/some-prompt"))
        }
    }
}
