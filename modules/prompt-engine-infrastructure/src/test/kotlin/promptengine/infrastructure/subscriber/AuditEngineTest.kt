package promptengine.infrastructure.subscriber

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import promptengine.domain.event.EventTopic
import promptengine.infrastructure.masking.SecretMaskingJsonSanitizer
import java.time.Instant
import java.util.UUID

/**
 * 全6トピック購読の監査書き込み（設計書§14「購読先: ... Audit」、ADR-0026決定4）。
 */
class AuditEngineTest {
    private val sanitizer = SecretMaskingJsonSanitizer(testObjectMapper)

    private companion object {
        const val REAL_SECRET = "sk-live-REAL-SECRET-VALUE"
    }

    private fun engine(repository: RecordingAuditRepository) = AuditEngine(repository, sanitizer)

    @Test
    fun `設計書14の6トピック全てを購読する`() {
        engine(RecordingAuditRepository()).topics shouldContainExactlyInAnyOrder EventTopic.entries.toList()
    }

    @Test
    fun `封筒の各項目をaudit_logsの列へ写す`() {
        val repository = RecordingAuditRepository()
        val eventId = UUID.randomUUID()
        val source =
            envelope(
                eventId = eventId,
                eventType = "PromptPublished",
                aggregateType = "Prompt",
                aggregateId = "support/faq",
                actor = "user:alice",
                traceId = "trace-42",
                occurredAt = Instant.parse("2026-08-09T12:00:00Z"),
            )

        engine(repository).handle(source)

        val entry = repository.entries.single()
        entry.aggregateType shouldBe "Prompt"
        entry.aggregateId shouldBe "support/faq"
        entry.action shouldBe "PromptPublished"
        entry.actor shouldBe "user:alice"
        entry.traceId shouldBe "trace-42"
        entry.occurredAt shouldBe Instant.parse("2026-08-09T12:00:00Z")
    }

    @Test
    fun `冪等キーとしてeventIdを渡す（再配信で監査行が二重にならないため）`() {
        val repository = RecordingAuditRepository()
        val eventId = UUID.randomUUID()

        engine(repository).handle(envelope(eventId = eventId))

        repository.entries.single().eventId shouldBe eventId
    }

    /**
     * `docs/prompts/p10b.md`のテスト要件「Auditのpayloadに Secret の実値が出ないこと（経路別テスト）」。
     * 設計書§14の6トピックそれぞれを代表するイベント種別で、Secretが保存内容へ出ないことを確認する。
     */
    @Test
    fun `6トピックいずれの経路でもpayloadにSecretの実値が出ない`() {
        val routes =
            listOf(
                "PromptPublished" to EventTopic.PE_PROMPT,
                "PromptExecuted" to EventTopic.PE_EXECUTION,
                "PromptEvaluationCompleted" to EventTopic.PE_EVALUATION,
                "ExperimentStarted" to EventTopic.PE_EXPERIMENT,
                "PromptApproved" to EventTopic.PE_GOVERNANCE,
                "PluginRegistered" to EventTopic.PE_PLUGIN,
            )

        routes.forEach { (eventType, topic) ->
            val repository = RecordingAuditRepository()

            engine(repository).handle(
                envelope(
                    eventType = eventType,
                    payload = """{"promptKey":"support/faq","apiSecret":"$REAL_SECRET"}""",
                ),
            )

            withClue("eventType=$eventType topic=${topic.topicName}") {
                repository.entries.single().payload shouldNotContain REAL_SECRET
            }
        }
    }

    @Test
    fun `入れ子や配列に埋まったSecretも保存内容に出ない`() {
        val repository = RecordingAuditRepository()

        engine(repository).handle(
            envelope(payload = """{"vars":[{"name":"k","token":"$REAL_SECRET"}],"deep":{"password":"$REAL_SECRET"}}"""),
        )

        repository.entries.single().payload shouldNotContain REAL_SECRET
    }

    @Test
    fun `保存に失敗した場合は例外を伝播させる（駆動側がDLQへ退避する）`() {
        val repository = RecordingAuditRepository(failWith = IllegalStateException("db down"))

        shouldThrow<IllegalStateException> { engine(repository).handle(envelope()) }
    }

    @Test
    fun `具象クラスが存在しないイベント種別でも保存できる`() {
        // AuditEngineは設計書§14の全イベントを無差別に保存する立場であり、
        // 発行側にKotlinの具象クラスがまだ無いイベント種別も受け取りうる。
        val repository = RecordingAuditRepository()

        engine(repository).handle(envelope(eventType = "ExperimentWinnerDeclared", payload = """{"winner":"B"}"""))

        repository.entries.single().action shouldBe "ExperimentWinnerDeclared"
    }
}
