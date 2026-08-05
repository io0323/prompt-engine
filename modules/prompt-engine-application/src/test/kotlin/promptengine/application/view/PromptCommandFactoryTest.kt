package promptengine.application.view

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.prompt.LifecycleState
import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.SemVer

class PromptCommandFactoryTest {
    private val meta = RequestMeta(actor = "user:test", traceId = "trace-1", idempotencyKey = "idem-1")

    @Test
    fun `createPromptCommand はcore-content-metaを正しくCommandへ変換する`() {
        val core =
            CreatePromptCoreInput(
                key = "support/faq-answer",
                name = "FAQ",
                category = "support",
                description = "desc",
                tags = listOf("faq"),
                semVer = "1.0.0",
                source = "---\nkey: support/faq-answer\n---\nhi",
            )
        val content =
            PromptVersionContentInput(
                variables = listOf(VariableDefinitionInput(name = "x", type = "STRING")),
                contextRequirements = listOf(ContextRequirementInput(scope = "conversation")),
            )

        val command = PromptCommandFactory.createPromptCommand(core, content, meta)

        command.key shouldBe PromptKey("support/faq-answer")
        command.name shouldBe "FAQ"
        command.semVer shouldBe SemVer(1, 0, 0)
        command.variables.size shouldBe 1
        command.contextRequirements.size shouldBe 1
        command.actor shouldBe "user:test"
        command.traceId shouldBe "trace-1"
        command.idempotencyKey shouldBe "idem-1"
    }

    @Test
    fun `createPromptCommand はkeyが不正な形式ならIllegalArgumentException`() {
        val core = CreatePromptCoreInput("no-slash", "n", null, null, emptyList(), "1.0.0", "src")
        shouldThrow<IllegalArgumentException> {
            PromptCommandFactory.createPromptCommand(core, PromptVersionContentInput(), meta)
        }
    }

    @Test
    fun `updatePromptMetadataCommand はkey-metadata-metaを正しくCommandへ変換する`() {
        val metadata = UpdatePromptMetadataInput(name = "N", category = "c", description = "d", tags = listOf("t"))

        val command = PromptCommandFactory.updatePromptMetadataCommand("support/faq-answer", metadata, meta)

        command.key shouldBe PromptKey("support/faq-answer")
        command.name shouldBe "N"
        command.actor shouldBe "user:test"
    }

    @Test
    fun `archiveCommand はkey-semVer-force-metaを正しくCommandへ変換する`() {
        val command = PromptCommandFactory.archiveCommand("support/faq-answer", "1.0.0", true, meta)

        command.key shouldBe PromptKey("support/faq-answer")
        command.semVer shouldBe SemVer(1, 0, 0)
        command.force shouldBe true
    }

    @Test
    fun `archiveCommand はsemVerが不正な形式ならIllegalArgumentException`() {
        shouldThrow<IllegalArgumentException> {
            PromptCommandFactory.archiveCommand("support/faq-answer", "not-a-semver", false, meta)
        }
    }

    @Test
    fun `setAliasCommand はkey-alias-semVer-metaを正しくCommandへ変換する`() {
        val command = PromptCommandFactory.setAliasCommand("support/faq-answer", "stable", "1.0.0", meta)

        command.key shouldBe PromptKey("support/faq-answer")
        command.alias shouldBe "stable"
        command.semVer shouldBe SemVer(1, 0, 0)
        command.actor shouldBe "user:test"
        command.traceId shouldBe "trace-1"
        command.idempotencyKey shouldBe "idem-1"
    }

    @Test
    fun `getPromptQuery はkeyをPromptKeyへ変換する`() {
        PromptCommandFactory.getPromptQuery("support/faq-answer").key shouldBe PromptKey("support/faq-answer")
    }

    @Test
    fun `searchPromptsQuery はfilters-pagingをPromptSearchCriteriaへ変換する`() {
        val filters = SearchFiltersInput(q = "faq", tag = "support", category = "cat", status = "Published")
        val paging = Paging(page = 2, size = 10)

        val query = PromptCommandFactory.searchPromptsQuery(filters, paging)

        query.criteria.q shouldBe "faq"
        query.criteria.tag shouldBe "support"
        query.criteria.category shouldBe "cat"
        query.criteria.status shouldBe LifecycleState.Published
        query.criteria.page shouldBe 2
        query.criteria.size shouldBe 10
    }

    @Test
    fun `searchPromptsQuery はstatusがnullならcriteria-statusもnull`() {
        val query = PromptCommandFactory.searchPromptsQuery(SearchFiltersInput(), Paging(0, 20))
        query.criteria.status shouldBe null
    }

    @Test
    fun `searchPromptsQuery はstatusが未知の文字列ならIllegalArgumentException`() {
        shouldThrow<IllegalArgumentException> {
            PromptCommandFactory.searchPromptsQuery(SearchFiltersInput(status = "unknown"), Paging(0, 20))
        }
    }
}
