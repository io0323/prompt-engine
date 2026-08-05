package promptengine.application.command

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.audit.AuditLogEntry
import promptengine.domain.audit.AuditQuery
import promptengine.domain.audit.AuditRecord
import promptengine.domain.audit.AuditRepository
import promptengine.domain.event.EventContext
import promptengine.domain.prompt.NewPromptVersion
import promptengine.domain.prompt.Prompt
import promptengine.domain.prompt.PromptContent
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.PromptRepository
import promptengine.domain.prompt.PromptVersionNotFoundException
import promptengine.domain.shared.Page
import promptengine.domain.shared.SemVer
import java.time.Instant

private class RecordingAuditRepository : AuditRepository {
    val recorded = mutableListOf<AuditLogEntry>()

    override fun append(record: AuditRecord) = Unit

    override fun record(entry: AuditLogEntry) {
        recorded.add(entry)
    }

    override fun search(query: AuditQuery): Page<AuditLogEntry> = Page(emptyList(), 0, 20, 0)
}

class SetAliasHandlerTest {
    private val promptKey = PromptKey("team/greeting")
    private val semVer = SemVer(1, 0, 0)
    private val context =
        EventContext(actor = "tester", traceId = "trace-1", occurredAt = Instant.parse("2026-01-01T00:00:00Z"))

    private fun handler(
        promptRepository: PromptRepository = InMemoryPromptRepository(),
        aliasRepository: InMemoryPromptAliasRepository = InMemoryPromptAliasRepository(),
        auditRepository: RecordingAuditRepository = RecordingAuditRepository(),
    ) = SetAliasHandler(promptRepository, aliasRepository, auditRepository, PassthroughIdempotentCommandExecutor())

    @Test
    fun `既存Versionへエイリアスを設定できる`() {
        val (draft, _) = Prompt.create(promptKey, NewPromptVersion(semVer, PromptContent("body")), context)
        val promptRepository = InMemoryPromptRepository().apply { seed(draft) }
        val aliasRepository = InMemoryPromptAliasRepository()

        val result =
            handler(promptRepository, aliasRepository)
                .handle(SetAliasCommand(promptKey, "stable", semVer, actor = "user:alice", traceId = "trace-42"))

        result.alias shouldBe "stable"
        aliasRepository.find(promptKey, "stable")?.semVer shouldBe semVer
    }

    @Test
    fun `エイリアス設定はactor-traceIdを添えてAuditRepositoryへ記録する`() {
        val (draft, _) = Prompt.create(promptKey, NewPromptVersion(semVer, PromptContent("body")), context)
        val promptRepository = InMemoryPromptRepository().apply { seed(draft) }
        val auditRepository = RecordingAuditRepository()

        handler(promptRepository, auditRepository = auditRepository)
            .handle(SetAliasCommand(promptKey, "stable", semVer, actor = "user:alice", traceId = "trace-42"))

        auditRepository.recorded.size shouldBe 1
        val entry = auditRepository.recorded.single()
        entry.aggregateType shouldBe "Prompt"
        entry.aggregateId shouldBe promptKey.value
        entry.action shouldBe "AliasSet"
        entry.actor shouldBe "user:alice"
        entry.traceId shouldBe "trace-42"
        entry.payload shouldBe """{"alias":"stable","semVer":"1.0.0"}"""
    }

    @Test
    fun `エイリアス名に引用符やバックスラッシュを含んでもpayloadは妥当なJSONへエスケープされる`() {
        val (draft, _) = Prompt.create(promptKey, NewPromptVersion(semVer, PromptContent("body")), context)
        val promptRepository = InMemoryPromptRepository().apply { seed(draft) }
        val auditRepository = RecordingAuditRepository()
        val alias = """weird"alias\with\backslashes"""

        handler(promptRepository, auditRepository = auditRepository)
            .handle(SetAliasCommand(promptKey, alias, semVer, actor = "user:alice", traceId = "trace-42"))

        val entry = auditRepository.recorded.single()
        entry.payload shouldBe """{"alias":"weird\"alias\\with\\backslashes","semVer":"1.0.0"}"""
    }

    @Test
    fun `存在しないVersionを指すエイリアスは拒否される`() {
        val (draft, _) = Prompt.create(promptKey, NewPromptVersion(semVer, PromptContent("body")), context)
        val promptRepository = InMemoryPromptRepository().apply { seed(draft) }

        shouldThrow<PromptVersionNotFoundException> {
            handler(promptRepository)
                .handle(
                    SetAliasCommand(promptKey, "stable", SemVer(9, 9, 9), actor = "user:alice", traceId = "trace-1"),
                )
        }
    }

    @Test
    fun `Promptが存在しなければ例外を投げる`() {
        shouldThrow<PromptVersionNotFoundException> {
            handler().handle(SetAliasCommand(promptKey, "stable", semVer, actor = "user:alice", traceId = "trace-1"))
        }
    }

    @Test
    fun `jsonEscaped は引用符をエスケープする`() {
        val input = "a" + '"' + "b"
        val expected = "a" + '\\' + '"' + "b"
        input.jsonEscaped() shouldBe expected
    }

    @Test
    fun `jsonEscaped はバックスラッシュをエスケープする`() {
        val input = "a" + '\\' + "b"
        val expected = "a" + '\\' + '\\' + "b"
        input.jsonEscaped() shouldBe expected
    }

    @Test
    fun `jsonEscaped は改行-復帰-タブをエスケープする`() {
        val input = "a" + '\n' + "b" + '\r' + "c" + '\t' + "d"
        val expected = "a\\nb\\rc\\td"
        input.jsonEscaped() shouldBe expected
    }

    @Test
    fun `jsonEscaped はその他の制御文字をuXXXX形式でエスケープする`() {
        val input = "a" + 1.toChar() + "b"
        val expected = "a\\u0001b"
        input.jsonEscaped() shouldBe expected
    }

    @Test
    fun `jsonEscaped は通常文字をそのまま通す`() {
        "stable-v1".jsonEscaped() shouldBe "stable-v1"
    }
}
