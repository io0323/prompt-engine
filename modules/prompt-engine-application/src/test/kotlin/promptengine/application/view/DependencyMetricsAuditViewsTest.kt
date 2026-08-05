package promptengine.application.view

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.application.query.AuditLogsHandler
import promptengine.application.query.DependenciesHandler
import promptengine.application.query.DependenciesQuery
import promptengine.application.query.DependencyDirection
import promptengine.application.query.DiffHandler
import promptengine.application.query.DiffQuery
import promptengine.application.query.GetVersionHandler
import promptengine.application.query.GetVersionQuery
import promptengine.application.query.MetricsHandler
import promptengine.application.query.MetricsQuery
import promptengine.application.query.SearchPromptsHandler
import promptengine.application.query.SearchPromptsQuery
import promptengine.domain.audit.AuditLogEntry
import promptengine.domain.audit.AuditQuery
import promptengine.domain.audit.AuditRepository
import promptengine.domain.dependency.DependencyEdge
import promptengine.domain.dependency.DependencyKind
import promptengine.domain.dependency.DependencyRepository
import promptengine.domain.event.EventContext
import promptengine.domain.metrics.MetricsRepository
import promptengine.domain.metrics.MetricsSummary
import promptengine.domain.prompt.NewPromptVersion
import promptengine.domain.prompt.Prompt
import promptengine.domain.prompt.PromptContent
import promptengine.domain.prompt.PromptDomainEvent
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.PromptRepository
import promptengine.domain.prompt.PromptSearchCriteria
import promptengine.domain.prompt.PromptSearchRepository
import promptengine.domain.prompt.PromptSummary
import promptengine.domain.shared.Cost
import promptengine.domain.shared.LatencyMs
import promptengine.domain.shared.Page
import promptengine.domain.shared.SemVer
import promptengine.domain.shared.TokenCount
import java.time.Instant
import java.util.UUID

class DependencyMetricsAuditViewsTest {
    private val eventContext = EventContext(actor = "user:test", traceId = "trace-1", occurredAt = Instant.EPOCH)
    private val key = PromptKey("support/faq-answer")
    private val semVer = SemVer(1, 0, 0)

    @Test
    fun `DependencyEdge-toView は全フィールドを変換する`() {
        val edge = DependencyEdge(key, semVer, DependencyKind.TEMPLATE, "templates/base", "1.0.0")

        val view = edge.toView()

        view.fromKey shouldBe "support/faq-answer"
        view.fromVersion shouldBe "1.0.0"
        view.toKind shouldBe "TEMPLATE"
        view.toKey shouldBe "templates/base"
        view.toVersion shouldBe "1.0.0"
    }

    @Test
    fun `MetricsSummary-toView は全フィールドを変換しsuccessRateを含む`() {
        val from = Instant.parse("2026-01-01T00:00:00Z")
        val to = Instant.parse("2026-01-02T00:00:00Z")
        val summary =
            MetricsSummary(
                promptKey = key,
                from = from,
                to = to,
                executionCount = 10,
                successCount = 8,
                totalInputTokens = TokenCount(100),
                totalOutputTokens = TokenCount(200),
                totalCost = Cost(java.math.BigDecimal("1.5")),
                averageLatency = LatencyMs(50),
            )

        val view = summary.toView()

        view.promptKey shouldBe "support/faq-answer"
        view.executionCount shouldBe 10
        view.successCount shouldBe 8
        view.successRate shouldBe 0.8
        view.totalInputTokens shouldBe 100
        view.totalOutputTokens shouldBe 200
        view.averageLatencyMs shouldBe 50
    }

    @Test
    fun `AuditLogEntry-toView は全フィールドを変換する`() {
        val id = UUID.randomUUID()
        val occurredAt = Instant.parse("2026-01-01T00:00:00Z")
        val entry = AuditLogEntry(id, "Prompt", key.value, "Published", "user:test", "{}", "trace-1", occurredAt)

        val view = entry.toView()

        view.auditId shouldBe id
        view.aggregateType shouldBe "Prompt"
        view.aggregateId shouldBe key.value
        view.action shouldBe "Published"
        view.occurredAt shouldBe occurredAt
    }

    private class FakePromptRepository : PromptRepository {
        private val prompts = mutableMapOf<PromptKey, Prompt>()

        fun put(prompt: Prompt) {
            prompts[prompt.key] = prompt
        }

        override fun findByKey(k: PromptKey): Prompt? = prompts[k]

        override fun save(
            prompt: Prompt,
            events: List<PromptDomainEvent>,
        ): Prompt {
            prompts[prompt.key] = prompt
            return prompt
        }
    }

    private fun wrap(k: String): String = "---\npe: \"1\"\nkind: prompt\nkey: $k\n---\nhello"

    private fun draftPrompt(): Prompt =
        Prompt.create(
            key,
            NewPromptVersion(semVer, PromptContent(wrap(key.value))),
            eventContext,
        ).first

    @Test
    fun `GetVersionHandler-handleView はhandle結果をPromptVersionViewへ変換する`() {
        val repo = FakePromptRepository().apply { put(draftPrompt()) }
        val handler = GetVersionHandler(repo)

        val view = handler.handleView(GetVersionQuery(key, semVer))

        view.semVer shouldBe "1.0.0"
    }

    @Test
    fun `DiffHandler-handleView はhandle結果をPromptVersionDiffViewへ変換する`() {
        val prompt = draftPrompt()
        val v2 =
            prompt.newVersion(
                NewPromptVersion(SemVer(1, 1, 0), PromptContent(wrap(key.value) + " v2")),
                eventContext,
            ).first
        val repo = FakePromptRepository().apply { put(v2) }
        val handler = DiffHandler(repo)

        val view = handler.handleView(DiffQuery(key, semVer, SemVer(1, 1, 0)))

        view.from shouldBe "1.0.0"
        view.to shouldBe "1.1.0"
        view.contentChanged shouldBe true
    }

    private class FakeDependencyRepository(private val outbound: List<DependencyEdge>) : DependencyRepository {
        override fun findOutbound(promptKey: PromptKey): List<DependencyEdge> = outbound

        override fun findOutbound(
            promptKey: PromptKey,
            semVer: SemVer,
        ): List<DependencyEdge> = outbound

        override fun findInbound(promptKey: PromptKey): List<DependencyEdge> = emptyList()

        override fun replaceOutbound(
            promptKey: PromptKey,
            semVer: SemVer,
            edges: List<DependencyEdge>,
        ) = Unit
    }

    @Test
    fun `DependenciesHandler-handleView はhandle結果を各DependencyEdgeViewへ変換する`() {
        val edge = DependencyEdge(key, semVer, DependencyKind.FRAGMENT, "fragments/x", null)
        val handler = DependenciesHandler(FakeDependencyRepository(listOf(edge)))

        val views = handler.handleView(DependenciesQuery(key, DependencyDirection.OUT))

        views shouldBe listOf(edge.toView())
    }

    private class FakeMetricsRepository(private val summary: MetricsSummary) : MetricsRepository {
        override fun summarize(
            promptKey: PromptKey,
            from: Instant,
            to: Instant,
        ): MetricsSummary = summary
    }

    @Test
    fun `MetricsHandler-handleView はhandle結果をMetricsSummaryViewへ変換する`() {
        val from = Instant.EPOCH
        val to = Instant.EPOCH.plusSeconds(3600)
        val summary =
            MetricsSummary(
                key, from, to, 1, 1,
                TokenCount(
                    1,
                ),
                TokenCount(1), Cost(java.math.BigDecimal.ZERO), LatencyMs(1),
            )
        val handler = MetricsHandler(FakeMetricsRepository(summary))

        val view = handler.handleView(MetricsQuery(key, from, to))

        view.promptKey shouldBe "support/faq-answer"
    }

    private class FakePromptSearchRepository(private val page: Page<PromptSummary>) : PromptSearchRepository {
        override fun search(criteria: PromptSearchCriteria): Page<PromptSummary> = page
    }

    @Test
    fun `SearchPromptsHandler-handleView はhandle結果をPageView-PromptSummaryViewへ変換する`() {
        val summary =
            PromptSummary(key, "FAQ", null, emptyList(), promptengine.domain.prompt.LifecycleState.Draft, "1.0.0", null)
        val page = Page(listOf(summary), 0, 20, 1)
        val handler = SearchPromptsHandler(FakePromptSearchRepository(page))

        val view = handler.handleView(SearchPromptsQuery(PromptSearchCriteria()))

        view.items shouldBe listOf(summary.toView())
        view.totalElements shouldBe 1
    }

    private class FakeAuditRepository(private val page: Page<AuditLogEntry>) : AuditRepository {
        override fun append(record: promptengine.domain.audit.AuditRecord) = Unit

        override fun record(entry: AuditLogEntry) = Unit

        override fun search(query: AuditQuery): Page<AuditLogEntry> = page
    }

    @Test
    fun `AuditLogsHandler-handleView はaggregateId-actor-range-pagingからAuditQueryを組み立てて変換する`() {
        val entry =
            AuditLogEntry(
                UUID.randomUUID(),
                "Prompt",
                key.value,
                "Published",
                "user:test",
                "{}",
                "trace-1",
                Instant.EPOCH,
            )
        val handler = AuditLogsHandler(FakeAuditRepository(Page(listOf(entry), 0, 20, 1)))

        val view =
            handler.handleView(
                "agg-1",
                "user:test",
                TimeRange(Instant.EPOCH, Instant.EPOCH.plusSeconds(1)),
                Paging(0, 20),
            )

        view.items shouldBe listOf(entry.toView())
    }
}
