package promptengine.engine.execution

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import promptengine.domain.execution.ExecutionAdapter
import promptengine.domain.execution.ExecutionPolicy
import promptengine.domain.execution.ParseRepairPolicy
import promptengine.domain.execution.RawResponse
import promptengine.domain.execution.Usage
import promptengine.domain.optimization.TokenBudgetExceededException
import promptengine.domain.parsing.OutputFormatter
import promptengine.domain.parsing.OutputSchema
import promptengine.domain.parsing.ParseFailedException
import promptengine.domain.parsing.ParsedOutput
import promptengine.domain.render.MessageRole
import promptengine.domain.render.OutputFormat
import promptengine.domain.render.RenderedMessage
import promptengine.domain.render.RenderedPrompt
import promptengine.domain.shared.LatencyMs
import promptengine.domain.shared.SensitiveValue
import promptengine.domain.shared.TokenCount
import promptengine.domain.tokenizer.TokenizerPlugin

/** contentが指定リストの値と一致する場合のみ成功し、それ以外は不正JSONとして失敗するテスト用formatter。 */
private class ValidatingJsonFormatter(private val validContents: Set<String>) : OutputFormatter {
    override fun format(): OutputFormat = OutputFormat.JSON

    override fun instruction(schema: OutputSchema?): String = "JSON形式で出力してください。"

    override fun parse(
        raw: String,
        schema: OutputSchema?,
    ): ParsedOutput =
        if (raw in validContents) {
            ParsedOutput(OutputFormat.JSON, fields = mapOf("answer" to raw), raw = raw)
        } else {
            throw ParseFailedException(OutputFormat.JSON, "invalid JSON syntax")
        }
}

/** 呼出順に固定のcontentを返すテスト専用[ExecutionAdapter]。 */
private class ScriptedContentExecutionAdapter(private val responses: List<String>) : ExecutionAdapter {
    var callCount: Int = 0
        private set
    val executedPrompts = mutableListOf<RenderedPrompt>()

    override fun execute(
        prompt: RenderedPrompt,
        policy: ExecutionPolicy,
    ): RawResponse {
        executedPrompts += prompt
        val content = responses[callCount]
        callCount++
        return RawResponse(SensitiveValue.of(content), Usage(TokenCount(1), TokenCount(1)), LatencyMs(1))
    }
}

class ExecutionCoordinatorTest {
    private val tokenizer = TokenizerPlugin { text -> TokenCount(text.length) }
    private val generousBudget = TokenCount(10_000)
    private val rendered =
        RenderedPrompt(
            listOf(RenderedMessage(MessageRole.SYSTEM, "answer in json")),
            OutputFormat.JSON,
            TokenCount(3),
            "hash",
        )

    @Test
    fun `初回で成功すればattemptsは1件`() {
        val adapter = ScriptedContentExecutionAdapter(listOf("valid"))
        val formatter = ValidatingJsonFormatter(setOf("valid"))
        val coordinator = ExecutionCoordinator(adapter, mapOf(OutputFormat.JSON to formatter), tokenizer)
        val policy = ExecutionPolicy(timeoutMs = 1000)

        val outcome = coordinator.run(rendered, policy, schema = null, budget = generousBudget)

        outcome.attempts.size shouldBe 1
        outcome.parsedOutput.fields shouldBe mapOf("answer" to "valid")
    }

    @Test
    fun `不正JSONの後に修復リトライで成功する`() {
        val adapter = ScriptedContentExecutionAdapter(listOf("broken", "valid"))
        val formatter = ValidatingJsonFormatter(setOf("valid"))
        val coordinator = ExecutionCoordinator(adapter, mapOf(OutputFormat.JSON to formatter), tokenizer)
        val policy = ExecutionPolicy(timeoutMs = 1000, parseRepair = ParseRepairPolicy(enabled = true, maxAttempts = 2))

        val outcome = coordinator.run(rendered, policy, schema = null, budget = generousBudget)

        outcome.attempts.size shouldBe 2
        outcome.parsedOutput.fields shouldBe mapOf("answer" to "valid")
        adapter.callCount shouldBe 2
    }

    @Test
    fun `修復用の再実行messagesは直前の失敗応答と修復指示を含む`() {
        val adapter = ScriptedContentExecutionAdapter(listOf("broken", "valid"))
        val formatter = ValidatingJsonFormatter(setOf("valid"))
        val coordinator = ExecutionCoordinator(adapter, mapOf(OutputFormat.JSON to formatter), tokenizer)
        val policy = ExecutionPolicy(timeoutMs = 1000, parseRepair = ParseRepairPolicy(enabled = true, maxAttempts = 2))

        coordinator.run(rendered, policy, schema = null, budget = generousBudget)

        val repairPrompt = adapter.executedPrompts[1]
        repairPrompt.messages.size shouldBe 3
        repairPrompt.messages[1].role shouldBe MessageRole.ASSISTANT
        repairPrompt.messages[1].content shouldBe "broken"
        repairPrompt.messages[2].role shouldBe MessageRole.USER
    }

    @Test
    fun `maxAttemptsを使い切ると最終的にPARSE_FAILEDを投げる`() {
        val adapter = ScriptedContentExecutionAdapter(listOf("broken1", "broken2", "broken3"))
        val formatter = ValidatingJsonFormatter(setOf("never-valid"))
        val coordinator = ExecutionCoordinator(adapter, mapOf(OutputFormat.JSON to formatter), tokenizer)
        val policy = ExecutionPolicy(timeoutMs = 1000, parseRepair = ParseRepairPolicy(enabled = true, maxAttempts = 2))

        val exception =
            shouldThrow<ParseFailedException> {
                coordinator.run(
                    rendered,
                    policy,
                    schema = null,
                    budget = generousBudget,
                )
            }

        exception.repairAttempts shouldBe 2
        adapter.callCount shouldBe 3
    }

    @Test
    fun `parseRepairが無効なら初回失敗で即座にPARSE_FAILEDを投げる`() {
        val adapter = ScriptedContentExecutionAdapter(listOf("broken"))
        val formatter = ValidatingJsonFormatter(setOf("valid"))
        val coordinator = ExecutionCoordinator(adapter, mapOf(OutputFormat.JSON to formatter), tokenizer)
        val policy = ExecutionPolicy(timeoutMs = 1000, parseRepair = ParseRepairPolicy(enabled = false))

        val exception =
            shouldThrow<ParseFailedException> {
                coordinator.run(
                    rendered,
                    policy,
                    schema = null,
                    budget = generousBudget,
                )
            }

        exception.repairAttempts shouldBe 0
        adapter.callCount shouldBe 1
    }

    @Test
    fun `修復ラウンドがトークン予算を超えるとTOKEN_BUDGET_EXCEEDEDを投げ再実行しない`() {
        val adapter = ScriptedContentExecutionAdapter(listOf("broken-response-that-is-fairly-long", "valid"))
        val formatter = ValidatingJsonFormatter(setOf("valid"))
        val coordinator = ExecutionCoordinator(adapter, mapOf(OutputFormat.JSON to formatter), tokenizer)
        val policy = ExecutionPolicy(timeoutMs = 1000, parseRepair = ParseRepairPolicy(enabled = true, maxAttempts = 2))
        val tinyBudget = TokenCount(10)

        val exception =
            shouldThrow<TokenBudgetExceededException> {
                coordinator.run(rendered, policy, schema = null, budget = tinyBudget)
            }

        exception.budget shouldBe tinyBudget
        adapter.callCount shouldBe 1
    }

    // ---- 秘密情報漏洩経路: 修復ループの失敗応答内容が例外メッセージに出ないこと（ADR-0014決定9） ----

    @Test
    fun `漏洩経路1 最終PARSE_FAILEDのメッセージに失敗応答中の秘密情報マーカーが含まれない`() {
        val secretMarker = "sk-real-secret-marker"
        val adapter = ScriptedContentExecutionAdapter(listOf("broken-$secretMarker-1", "broken-$secretMarker-2"))
        val formatter = ValidatingJsonFormatter(setOf("never-valid"))
        val coordinator = ExecutionCoordinator(adapter, mapOf(OutputFormat.JSON to formatter), tokenizer)
        val policy = ExecutionPolicy(timeoutMs = 1000, parseRepair = ParseRepairPolicy(enabled = true, maxAttempts = 1))

        val exception =
            shouldThrow<ParseFailedException> {
                coordinator.run(
                    rendered,
                    policy,
                    schema = null,
                    budget = generousBudget,
                )
            }

        exception.message.shouldNotContain(secretMarker)
        exception.reason.shouldNotContain(secretMarker)
    }

    @Test
    fun `漏洩経路2 修復ラウンドのRenderedPrompt自体は実行に必要なため失敗応答の生値を保持する`() {
        val secretMarker = "sk-real-secret-marker"
        val adapter = ScriptedContentExecutionAdapter(listOf("broken-$secretMarker", "valid"))
        val formatter = ValidatingJsonFormatter(setOf("valid"))
        val coordinator = ExecutionCoordinator(adapter, mapOf(OutputFormat.JSON to formatter), tokenizer)
        val policy = ExecutionPolicy(timeoutMs = 1000, parseRepair = ParseRepairPolicy(enabled = true, maxAttempts = 1))

        coordinator.run(rendered, policy, schema = null, budget = generousBudget)

        adapter.executedPrompts[1].messages[1].content shouldBe "broken-$secretMarker"
    }

    @Test
    fun `漏洩経路3 実行が成功した場合RawResponse contentに秘密情報が含まれていても例外は発生せず結果にのみ渡る`() {
        val secretMarker = "sk-real-secret-marker"
        val adapter = ScriptedContentExecutionAdapter(listOf("content-with-$secretMarker"))
        val formatter = ValidatingJsonFormatter(setOf("content-with-$secretMarker"))
        val coordinator = ExecutionCoordinator(adapter, mapOf(OutputFormat.JSON to formatter), tokenizer)
        val policy = ExecutionPolicy(timeoutMs = 1000)

        val outcome = coordinator.run(rendered, policy, schema = null, budget = generousBudget)

        outcome.parsedOutput.raw shouldBe "content-with-$secretMarker"
        outcome.attempts.single().content.expose() shouldBe "content-with-$secretMarker"
    }

    @Test
    fun `漏洩経路4 修復成功時のExecutionOutcome attemptsは失敗応答のcontentを記録せずマスクする`() {
        val secretMarker = "sk-real-secret-marker"
        val adapter =
            ScriptedContentExecutionAdapter(listOf("broken-$secretMarker-1", "broken-$secretMarker-2", "valid"))
        val formatter = ValidatingJsonFormatter(setOf("valid"))
        val coordinator = ExecutionCoordinator(adapter, mapOf(OutputFormat.JSON to formatter), tokenizer)
        val policy = ExecutionPolicy(timeoutMs = 1000, parseRepair = ParseRepairPolicy(enabled = true, maxAttempts = 2))

        val outcome = coordinator.run(rendered, policy, schema = null, budget = generousBudget)

        outcome.attempts.size shouldBe 3
        outcome.attempts[0].content.expose().shouldNotContain(secretMarker)
        outcome.attempts[1].content.expose().shouldNotContain(secretMarker)
        outcome.attempts.last().content.expose() shouldBe "valid"
    }

    @Test
    fun `outputFormatterが登録されていないoutputFormatを指定するとエラーを投げる`() {
        val adapter = ScriptedContentExecutionAdapter(listOf("valid"))
        val coordinator = ExecutionCoordinator(adapter, emptyMap(), tokenizer)
        val policy = ExecutionPolicy(timeoutMs = 1000)

        shouldThrow<IllegalStateException> { coordinator.run(rendered, policy, schema = null, budget = generousBudget) }
    }
}
