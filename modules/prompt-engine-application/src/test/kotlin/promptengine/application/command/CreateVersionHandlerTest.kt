package promptengine.application.command

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.dependency.DependencyEdge
import promptengine.domain.dependency.DependencyKind
import promptengine.domain.dependency.DependencyRepository
import promptengine.domain.event.EventContext
import promptengine.domain.prompt.NewPromptVersion
import promptengine.domain.prompt.Prompt
import promptengine.domain.prompt.PromptContent
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.PromptVersionNotFoundException
import promptengine.domain.shared.SemVer
import promptengine.engine.compiler.ExtendsFieldResolverImpl
import java.time.Instant

/**
 * Issue #21: Version作成コマンドは呼び出し側から`ExtendsRef`を個別引数として受け取らず、必ず
 * [ExtendsFieldResolverImpl]（実装、`prompt-engine-core`）を通して`content.source`から導出する。
 * 「保存された`ExtendsRef` == `content.source`をパースした結果」を検証する。
 */
class CreateVersionHandlerTest {
    private val promptKey = PromptKey("support/faq-answer")
    private val existingSemVer = SemVer(1, 0, 0)
    private val newSemVer = SemVer(1, 1, 0)

    private val source =
        """
        ---
        pe: "1"
        kind: prompt
        key: support/faq-answer
        name: FAQ回答生成
        extends: templates/base-assistant@^2
        ---
        {{#block user}}hello{{/block}}
        """.trimIndent()

    private class CapturingDependencyRepository : DependencyRepository {
        val captured = mutableListOf<DependencyEdge>()

        override fun findOutbound(promptKey: PromptKey): List<DependencyEdge> = emptyList()

        override fun findOutbound(
            promptKey: PromptKey,
            semVer: SemVer,
        ): List<DependencyEdge> = emptyList()

        override fun findInbound(promptKey: PromptKey): List<DependencyEdge> = emptyList()

        override fun replaceOutbound(
            promptKey: PromptKey,
            semVer: SemVer,
            edges: List<DependencyEdge>,
        ) {
            captured += edges
        }
    }

    @Test
    fun `保存されたExtendsRefはcontent_sourceをExtendsFieldResolverで直接解決した結果と一致する`() {
        val context =
            EventContext(actor = "tester", traceId = "trace-1", occurredAt = Instant.parse("2026-01-01T00:00:00Z"))
        val existingVersion = NewPromptVersion(semVer = existingSemVer, content = PromptContent("original body"))
        val (seedPrompt, _) = Prompt.create(promptKey, existingVersion, context)
        val promptRepository = InMemoryPromptRepository().apply { seed(seedPrompt) }
        val dependencyRepository = CapturingDependencyRepository()
        val resolver = ExtendsFieldResolverImpl()
        val handler =
            CreateVersionHandler(
                promptRepository,
                dependencyRepository,
                resolver,
                PassthroughIdempotentCommandExecutor(),
            )

        handler.handle(
            CreateVersionCommand(promptKey, newSemVer, source, actor = "tester", traceId = "trace-1"),
        )

        val expectedExtends = resolver.resolve(source)
        val savedVersion = promptRepository.findByKey(promptKey)!!.versions.find { it.semVer == newSemVer }!!
        savedVersion.extends shouldBe expectedExtends

        dependencyRepository.captured.size shouldBe 1
        dependencyRepository.captured.single().toKind shouldBe DependencyKind.TEMPLATE
        dependencyRepository.captured.single().toKey shouldBe expectedExtends!!.key.value
    }

    @Test
    fun `Promptが存在しなければ例外を投げる`() {
        val handler =
            CreateVersionHandler(
                InMemoryPromptRepository(),
                CapturingDependencyRepository(),
                ExtendsFieldResolverImpl(),
                PassthroughIdempotentCommandExecutor(),
            )

        shouldThrow<PromptVersionNotFoundException> {
            handler.handle(CreateVersionCommand(promptKey, newSemVer, source, actor = "tester", traceId = "trace-1"))
        }
    }
}
