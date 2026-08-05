package promptengine.application.view

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.VersionRef
import promptengine.domain.shared.SemVer

class VersionCommandFactoryTest {
    private val meta = RequestMeta(actor = "user:test", traceId = "trace-1", idempotencyKey = "idem-1")

    @Test
    fun `createVersionCommand はcore-content-metaを正しくCommandへ変換する`() {
        val core = CreateVersionCoreInput(key = "support/faq-answer", semVer = "1.1.0", source = "src")
        val content = PromptVersionContentInput(output = OutputDeclarationInput(format = "JSON"))

        val command = VersionCommandFactory.createVersionCommand(core, content, meta)

        command.key shouldBe PromptKey("support/faq-answer")
        command.semVer shouldBe SemVer(1, 1, 0)
        command.source shouldBe "src"
        command.output?.format?.name shouldBe "JSON"
        command.actor shouldBe "user:test"
    }

    @Test
    fun `createVersionCommand はsemVerが不正な形式ならIllegalArgumentException`() {
        val core = CreateVersionCoreInput("support/faq-answer", "bad", "src")
        shouldThrow<IllegalArgumentException> {
            VersionCommandFactory.createVersionCommand(core, PromptVersionContentInput(), meta)
        }
    }

    @Test
    fun `publishCommand はkey-semVer-metaを正しくCommandへ変換する`() {
        val command = VersionCommandFactory.publishCommand("support/faq-answer", "1.0.0", meta)

        command.key shouldBe PromptKey("support/faq-answer")
        command.semVer shouldBe SemVer(1, 0, 0)
        command.actor shouldBe "user:test"
    }

    @Test
    fun `rollbackCommand はkey-targetSemVer-metaを正しくCommandへ変換する`() {
        val command = VersionCommandFactory.rollbackCommand("support/faq-answer", "1.0.0", meta)

        command.key shouldBe PromptKey("support/faq-answer")
        command.targetSemVer shouldBe SemVer(1, 0, 0)
    }

    @Test
    fun `deprecateCommand はrecommendedReplacementがnullなら未指定のまま変換する`() {
        val command = VersionCommandFactory.deprecateCommand("support/faq-answer", "1.0.0", null, meta)

        command.recommendedReplacement shouldBe null
    }

    @Test
    fun `deprecateCommand はrecommendedReplacementをVersionRefへ変換する`() {
        val command = VersionCommandFactory.deprecateCommand("support/faq-answer", "1.0.0", "stable", meta)

        command.recommendedReplacement shouldBe VersionRef.Alias("stable")
    }

    @Test
    fun `getVersionQuery はkey-semVerをそれぞれ変換する`() {
        val query = VersionCommandFactory.getVersionQuery("support/faq-answer", "1.0.0")

        query.key shouldBe PromptKey("support/faq-answer")
        query.semVer shouldBe SemVer(1, 0, 0)
    }

    @Test
    fun `diffQuery はkey-from-toをそれぞれ変換する`() {
        val query = VersionCommandFactory.diffQuery("support/faq-answer", "1.0.0", "1.1.0")

        query.key shouldBe PromptKey("support/faq-answer")
        query.from shouldBe SemVer(1, 0, 0)
        query.to shouldBe SemVer(1, 1, 0)
    }

    @Test
    fun `diffQuery はfromが不正な形式ならIllegalArgumentException`() {
        shouldThrow<IllegalArgumentException> {
            VersionCommandFactory.diffQuery("support/faq-answer", "bad", "1.1.0")
        }
    }
}
