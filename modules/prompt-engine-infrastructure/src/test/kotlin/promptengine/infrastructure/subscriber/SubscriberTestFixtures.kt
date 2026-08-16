package promptengine.infrastructure.subscriber

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import promptengine.domain.audit.AuditLogEntry
import promptengine.domain.audit.AuditQuery
import promptengine.domain.audit.AuditRecord
import promptengine.domain.audit.AuditRepository
import promptengine.domain.cache.CacheKey
import promptengine.domain.cache.CachedItem
import promptengine.domain.cache.PromptCache
import promptengine.domain.dependency.DependencyEdge
import promptengine.domain.dependency.DependencyKind
import promptengine.domain.dependency.DependencyRepository
import promptengine.domain.dlq.DeadLetterEntry
import promptengine.domain.dlq.DeadLetterQueueRepository
import promptengine.domain.evaluation.EvaluationRecord
import promptengine.domain.evaluation.EvaluationRepository
import promptengine.domain.evaluation.ExecutionLogEntry
import promptengine.domain.evaluation.ExecutionLogRepository
import promptengine.domain.event.DomainEvent
import promptengine.domain.event.EventBusAdapter
import promptengine.domain.event.EventEnvelope
import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.Page
import promptengine.domain.shared.SemVer
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** 購読側テストが共有するフィクスチャ（本番コードではない）。 */
internal val testObjectMapper: ObjectMapper = jacksonObjectMapper()

// テストデータビルダーは「全項目に既定値を持ち、テストが関心のある項目だけ名前付き引数で
// 上書きする」形が読みやすさの要。項目数＝EventEnvelopeの封筒フィールド数であり、
// まとめ型を挟むとテスト側の記述がかえって冗長になるためLongParameterListを抑止する。
@Suppress("LongParameterList")
internal fun envelope(
    eventId: UUID = UUID.randomUUID(),
    eventType: String = "PromptExecuted",
    aggregateType: String = "Prompt",
    aggregateId: String = "support/faq",
    actor: String = "system",
    traceId: String = "trace-1",
    payload: String = promptExecutedPayload(),
    occurredAt: Instant = Instant.parse("2026-08-09T00:00:00Z"),
) = EventEnvelope(eventId, eventType, aggregateType, aggregateId, actor, traceId, payload, occurredAt)

@Suppress("LongParameterList")
internal fun promptExecutedPayload(
    promptKey: String = "support/faq",
    major: Int = 1,
    minor: Int = 0,
    patch: Int = 0,
    inputTokens: Int = 800,
    outputTokens: Int = 200,
    latencyMs: Long = 250,
    costPerToken: String = "0.0004",
    retryCount: Int = 0,
    status: String = "SUCCESS",
): String =
    """
    {"promptKey":"$promptKey","semVer":{"major":$major,"minor":$minor,"patch":$patch},
     "inputTokens":$inputTokens,"outputTokens":$outputTokens,"retryCount":$retryCount,
     "latencyMs":$latencyMs,"costPerToken":$costPerToken,"status":"$status"}
    """.trimIndent()

/** [CacheInvalidationSubscriber]向け: `PromptPublished`等の`payload`（`promptKey`+`semVer`）。 */
@Suppress("LongParameterList")
internal fun promptKeyedPayload(
    field: String = "promptKey",
    key: String = "support/faq",
    major: Int = 1,
    minor: Int = 0,
    patch: Int = 0,
): String = """{"$field":"$key","semVer":{"major":$major,"minor":$minor,"patch":$patch}}"""

/** [PromptCache]のテスト用記録実装。`invalidated`で無効化されたPromptKeyの履歴を確認できる。 */
internal class RecordingPromptCache : PromptCache {
    private val store = mutableMapOf<CacheKey, CachedItem>()
    val invalidated = mutableListOf<PromptKey>()

    override fun get(key: CacheKey): CachedItem? = store[key]

    override fun put(
        key: CacheKey,
        item: CachedItem,
        ttl: Duration,
    ) {
        store[key] = item
    }

    override fun invalidateByPrompt(key: PromptKey) {
        invalidated += key
        store.keys.filter { it.promptKey == key }.forEach { store.remove(it) }
    }

    fun snapshot(key: CacheKey): CachedItem? = store[key]
}

/** [DependencyRepository]のテスト用固定実装。逆依存の一覧をそのまま返す。 */
internal class FakeDependencyRepository(
    private val inboundTemplateOrFragment: List<DependencyEdge> = emptyList(),
) : DependencyRepository {
    override fun findOutbound(promptKey: PromptKey): List<DependencyEdge> = emptyList()

    override fun findOutbound(
        promptKey: PromptKey,
        semVer: SemVer,
    ): List<DependencyEdge> = emptyList()

    override fun findInbound(promptKey: PromptKey): List<DependencyEdge> = emptyList()

    override fun findInboundTemplateOrFragment(
        kind: DependencyKind,
        key: String,
    ): List<DependencyEdge> = inboundTemplateOrFragment.filter { it.toKind == kind && it.toKey == key }

    override fun replaceOutbound(
        promptKey: PromptKey,
        semVer: SemVer,
        edges: List<DependencyEdge>,
    ) = Unit
}

internal class RecordingAuditRepository(private val failWith: Throwable? = null) : AuditRepository {
    val entries = mutableListOf<AuditLogEntry>()
    val appended = mutableListOf<AuditRecord>()

    override fun append(record: AuditRecord) {
        failWith?.let { throw it }
        appended += record
    }

    override fun record(entry: AuditLogEntry) {
        failWith?.let { throw it }
        entries += entry
    }

    override fun search(query: AuditQuery): Page<AuditLogEntry> =
        Page(
            entries.toList(),
            0,
            entries.size.coerceAtLeast(1),
            entries.size.toLong(),
        )
}

internal class RecordingExecutionLogRepository(private val failWith: Throwable? = null) : ExecutionLogRepository {
    val appended = mutableListOf<ExecutionLogEntry>()

    override fun append(entry: ExecutionLogEntry): Boolean {
        failWith?.let { throw it }
        appended += entry
        return true
    }

    override fun hasExecutionSince(
        key: PromptKey,
        semVer: SemVer,
        since: Instant,
    ): Boolean = appended.any { it.promptKey == key.value && it.semVer == semVer && it.executedAt >= since }
}

internal class RecordingEvaluationRepository(private val insertedCount: Int? = null) : EvaluationRepository {
    val saved = mutableListOf<EvaluationRecord>()

    override fun saveAll(records: List<EvaluationRecord>): Int {
        saved += records
        return insertedCount ?: records.size
    }
}

internal class RecordingEventBusAdapter : EventBusAdapter {
    val published = mutableListOf<DomainEvent>()

    override fun publish(event: DomainEvent) {
        published += event
    }
}

internal class RecordingDeadLetterQueueRepository : DeadLetterQueueRepository {
    val enqueued = mutableListOf<DeadLetterEntry>()

    override fun enqueue(entry: DeadLetterEntry) {
        enqueued += entry
    }

    override fun pendingCount(): Long = enqueued.size.toLong()
}
