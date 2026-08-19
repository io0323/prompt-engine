package promptengine.application.pipeline

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import promptengine.domain.evaluation.ExecutionStatus
import promptengine.domain.event.DomainEvent
import promptengine.domain.event.EventBusAdapter
import promptengine.domain.event.EventContext
import promptengine.domain.execution.ExecutionOutcome
import promptengine.domain.execution.ExecutionPolicy
import promptengine.domain.execution.RawResponse
import promptengine.domain.execution.Usage
import promptengine.domain.optimization.ModelProfile
import promptengine.domain.parsing.ParsedOutput
import promptengine.domain.pipeline.PipelineContext
import promptengine.domain.pipeline.PipelineMode
import promptengine.domain.pipeline.PipelineRequest
import promptengine.domain.pipeline.PromptExecutedEvent
import promptengine.domain.prompt.NewPromptVersion
import promptengine.domain.prompt.Prompt
import promptengine.domain.prompt.PromptContent
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.PromptVersion
import promptengine.domain.prompt.VersionRef
import promptengine.domain.render.MessageRole
import promptengine.domain.render.ModelHints
import promptengine.domain.render.OutputFormat
import promptengine.domain.render.RenderedMessage
import promptengine.domain.render.RenderedPrompt
import promptengine.domain.shared.Cost
import promptengine.domain.shared.LatencyMs
import promptengine.domain.shared.PromptRequest
import promptengine.domain.shared.SemVer
import promptengine.domain.shared.SensitiveValue
import promptengine.domain.shared.TokenCount
import java.math.BigDecimal
import java.time.Instant

/**
 * Stage 11（Evaluation）が発行する[PromptExecutedEvent]のpayload内容を固定する。
 *
 * P10bで`execution_logs`（設計書§12）とEvaluationのLatency/Cost指標（設計書§2.12）を
 * 算出するために`semVer`/`latencyMs`/`costPerToken`/`status`を追加した（ADR-0026決定3）。
 * 「本流をブロックしない・失敗させない」契約（設計書§2.6）も併せて固定する。
 */
class EvaluationStageTest {
    private val semVer = SemVer(1, 2, 3)
    private val promptKey = PromptKey("support/faq")

    private class CapturingEventBusAdapter : EventBusAdapter {
        val published = mutableListOf<DomainEvent>()

        override fun publish(event: DomainEvent) {
            published += event
        }
    }

    private fun promptVersion(): PromptVersion {
        val eventContext = EventContext(actor = "tester", traceId = "trace-1", occurredAt = Instant.EPOCH)
        val (prompt, _) =
            Prompt.create(promptKey, NewPromptVersion(semVer = semVer, content = PromptContent("body")), eventContext)
        return prompt.versions.single()
    }

    private fun modelProfile(costPerToken: String) =
        ModelProfile(
            maxContextTokens = TokenCount(1_000),
            tokenizerId = "test-tokenizer",
            costPerToken = Cost(BigDecimal(costPerToken)),
        )

    private fun rawResponse(
        inputTokens: Int = 800,
        outputTokens: Int = 200,
        latencyMs: Long = 90,
        retryCount: Int = 0,
    ) = RawResponse(
        content = SensitiveValue.of("response body"),
        usage = Usage(TokenCount(inputTokens), TokenCount(outputTokens)),
        latency = LatencyMs(latencyMs),
        retryCount = retryCount,
    )

    private fun context(
        attempts: List<RawResponse> = listOf(rawResponse()),
        stageDurationsMs: Map<String, Long> = mapOf("Execution" to 250L),
        costPerToken: String = "0.0004",
        withPromptVersion: Boolean = true,
        rendered: RenderedPrompt? = null,
    ): PipelineContext =
        PipelineContext(
            request =
                PipelineRequest(
                    promptKey = promptKey,
                    versionRef = VersionRef.Latest,
                    variableResolution = PromptRequest(),
                    modelProfile = modelProfile(costPerToken),
                    budget = TokenCount(1_000),
                    executionPolicy = ExecutionPolicy(timeoutMs = 1_000),
                ),
            mode = PipelineMode.FULL_EXECUTION,
            traceId = "trace-eval",
            promptVersion = if (withPromptVersion) promptVersion() else null,
            rendered = rendered,
            executionOutcome =
                ExecutionOutcome(
                    parsedOutput = ParsedOutput(format = OutputFormat.TEXT, raw = "response body"),
                    attempts = attempts,
                ),
            stageDurationsMs = stageDurationsMs,
        )

    private fun renderedPrompt(modelHints: ModelHints? = null) =
        RenderedPrompt(
            messages = listOf(RenderedMessage(MessageRole.USER, "hi")),
            outputFormat = OutputFormat.TEXT,
            tokenEstimate = TokenCount(2),
            renderHash = "hash",
            modelHints = modelHints,
        )

    private fun publishedPayload(adapter: CapturingEventBusAdapter): PromptExecutedEvent.Payload =
        adapter.published.single().shouldBeInstanceOf<PromptExecutedEvent>().payload

    @Test
    fun `payloadにpromptKeyとsemVerとusageとretryCountを載せる`() {
        val adapter = CapturingEventBusAdapter()

        EvaluationStage(adapter).execute(context(attempts = listOf(rawResponse(retryCount = 2))))

        val payload = publishedPayload(adapter)
        payload.promptKey shouldBe "support/faq"
        payload.semVer shouldBe semVer
        payload.inputTokens shouldBe 800
        payload.outputTokens shouldBe 200
        payload.retryCount shouldBe 2
    }

    @Test
    fun `latencyMsはExecutionステージの実測durationを使う`() {
        val adapter = CapturingEventBusAdapter()

        EvaluationStage(adapter).execute(context(stageDurationsMs = mapOf("Execution" to 250L)))

        publishedPayload(adapter).latencyMs shouldBe 250L
    }

    @Test
    fun `Executionのdurationが未計測なら各試行のlatency合算へフォールバックする`() {
        val adapter = CapturingEventBusAdapter()

        EvaluationStage(adapter).execute(
            context(
                attempts = listOf(rawResponse(latencyMs = 90), rawResponse(latencyMs = 60)),
                stageDurationsMs = emptyMap(),
            ),
        )

        publishedPayload(adapter).latencyMs shouldBe 150L
    }

    @Test
    fun `costPerTokenは実行時点のModelProfile単価を載せる`() {
        val adapter = CapturingEventBusAdapter()

        EvaluationStage(adapter).execute(context(costPerToken = "0.0004"))

        publishedPayload(adapter).costPerToken shouldBe BigDecimal("0.0004")
    }

    @Test
    fun `statusはStage 11へ到達している以上常にSUCCESS`() {
        val adapter = CapturingEventBusAdapter()

        EvaluationStage(adapter).execute(context())

        publishedPayload(adapter).status shouldBe ExecutionStatus.SUCCESS
    }

    // ---- temperature（ADR-0035決定5）: renderHashから除外する分、実行記録側に残す ----

    @Test
    fun `renderedのmodelHintsが持つtemperatureをpayloadへ載せる`() {
        val adapter = CapturingEventBusAdapter()

        EvaluationStage(adapter).execute(context(rendered = renderedPrompt(ModelHints(temperature = 0.0))))

        publishedPayload(adapter).temperature shouldBe 0.0
    }

    @Test
    fun `renderedが持たない または modelHintsがnullならtemperatureはnull`() {
        val adapter = CapturingEventBusAdapter()

        EvaluationStage(adapter).execute(context(rendered = renderedPrompt(modelHints = null)))

        publishedPayload(adapter).temperature shouldBe null
    }

    @Test
    fun `renderedがnullでも本流を失敗させずtemperatureはnullになる`() {
        val adapter = CapturingEventBusAdapter()

        EvaluationStage(adapter).execute(context(rendered = null))

        publishedPayload(adapter).temperature shouldBe null
    }

    @Test
    fun `actorは既定でsystem（呼出元識別の伝搬経路がM1に無いため）`() {
        val adapter = CapturingEventBusAdapter()

        EvaluationStage(adapter).execute(context())

        adapter.published.single().actor shouldBe "system"
    }

    @Test
    fun `promptVersion未設定でも本流を失敗させずpublishも呼ばない`() {
        val adapter = CapturingEventBusAdapter()
        val bare = context(withPromptVersion = false)

        val result = EvaluationStage(adapter).execute(bare)

        result shouldBe bare
        adapter.published shouldBe emptyList()
    }

    @Test
    fun `publishが例外を投げても本流は失敗せずcontextをそのまま返す`() {
        val failingAdapter =
            object : EventBusAdapter {
                override fun publish(event: DomainEvent): Unit = error("broker down")
            }
        val input = context()

        EvaluationStage(failingAdapter).execute(input) shouldBe input
    }

    /**
     * 設計書§2.6「イベント発行のみ（非同期評価）」。Stageは`EventBusAdapter.publish`を1回呼ぶ
     * だけで、評価そのもの（`EvaluationEngine`）へは同期的に触れない。実際の評価は
     * Broker経由の購読側（`EvaluationSubscriber`、`prompt-engine-infrastructure`）が行う。
     */
    @Test
    fun `Stageはイベント発行のみでEvaluationEngineを同期呼び出ししない`() {
        val adapter = CapturingEventBusAdapter()

        EvaluationStage(adapter).execute(context())

        adapter.published.size shouldBe 1
        adapter.published.single().shouldBeInstanceOf<PromptExecutedEvent>()
    }
}
