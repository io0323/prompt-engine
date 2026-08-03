package promptengine.application.command

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.SemVer
import promptengine.engine.compiler.ExtendsFieldResolverImpl

class CreatePromptHandlerTest {
    private val promptKey = PromptKey("team/greeting")
    private val semVer = SemVer(1, 0, 0)
    private val source =
        """
        ---
        pe: "1"
        kind: prompt
        key: team/greeting
        name: 挨拶
        ---
        {{#block user}}hello{{/block}}
        """.trimIndent()

    @Test
    fun `Draft状態のPromptを作成しメタデータを保存しPromptCreatedを記録する`() {
        val promptRepository = InMemoryPromptRepository()
        val metadataRepository = InMemoryPromptMetadataRepository()
        val dependencyRepository = InMemoryDependencyRepository()
        val handler =
            CreatePromptHandler(
                promptRepository,
                metadataRepository,
                dependencyRepository,
                ExtendsFieldResolverImpl(),
                PassthroughIdempotentCommandExecutor(),
            )

        val result =
            handler.handle(
                CreatePromptCommand(
                    key = promptKey,
                    name = "挨拶",
                    semVer = semVer,
                    source = source,
                    actor = "tester",
                    traceId = "trace-1",
                ),
            )

        result.key shouldBe promptKey
        promptRepository.findByKey(promptKey) shouldBe promptRepository.findByKey(promptKey)
        metadataRepository.find(promptKey)?.name shouldBe "挨拶"
        promptRepository.savedEvents.size shouldBe 1
    }
}
