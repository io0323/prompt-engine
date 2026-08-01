package promptengine.domain.pipeline

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import promptengine.domain.execution.ExecutionPolicy
import promptengine.domain.optimization.ModelProfile
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.VersionRef
import promptengine.domain.shared.Cost
import promptengine.domain.shared.PromptRequest
import promptengine.domain.shared.TokenCount
import java.math.BigDecimal

/** [PipelineContext]の契約テスト（設計書§3.4疑似コード、ADR-0015決定3）。 */
class PipelineContextTest {
    private val modelProfile =
        ModelProfile(
            maxContextTokens = TokenCount(1_000),
            tokenizerId = "test-tokenizer",
            costPerToken = Cost(BigDecimal.ZERO),
        )
    private val request =
        PipelineRequest(
            promptKey = PromptKey("support/faq"),
            versionRef = VersionRef.Latest,
            variableResolution = PromptRequest(),
            modelProfile = modelProfile,
            budget = TokenCount(1_000),
        )

    @Test
    fun `新規構築時は request mode traceId以外のフィールドがnullまたは空である`() {
        val context = PipelineContext(request = request, mode = PipelineMode.RENDER_ONLY, traceId = "trace-1")

        context.promptVersion shouldBe null
        context.compiled shouldBe null
        context.variableBindings shouldBe null
        context.contextBindings shouldBe null
        context.validationReport shouldBe null
        context.rendered shouldBe null
        context.executionOutcome shouldBe null
        context.parsedOutput shouldBe null
        context.stageDurationsMs shouldBe emptyMap()
    }

    @Test
    fun `copy で不変更新でき 元のインスタンスは変化しない`() {
        val original = PipelineContext(request = request, mode = PipelineMode.FULL_EXECUTION, traceId = "trace-1")

        val updated = original.copy(stageDurationsMs = mapOf("Load" to 5L))

        updated shouldNotBe original
        original.stageDurationsMs shouldBe emptyMap()
        updated.stageDurationsMs shouldBe mapOf("Load" to 5L)
    }

    @Test
    fun `同じフィールド値を持つ2つのインスタンスは等価である`() {
        val a = PipelineContext(request = request, mode = PipelineMode.COMPILE_ONLY, traceId = "trace-x")
        val b = PipelineContext(request = request, mode = PipelineMode.COMPILE_ONLY, traceId = "trace-x")

        a shouldBe b
        a.hashCode() shouldBe b.hashCode()
    }

    @Test
    fun `requestがexecutionPolicyを持つ場合も保持される`() {
        val requestWithPolicy = request.copy(executionPolicy = ExecutionPolicy(timeoutMs = 5_000))

        val context =
            PipelineContext(request = requestWithPolicy, mode = PipelineMode.FULL_EXECUTION, traceId = "trace-1")

        context.request.executionPolicy shouldBe ExecutionPolicy(timeoutMs = 5_000)
    }
}
