package promptengine.application.pipeline

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.application.view.ExecutionPolicyInput
import promptengine.application.view.OutputSchemaFieldInput
import promptengine.application.view.OutputSchemaInput
import promptengine.domain.optimization.ModelProfile
import promptengine.domain.parsing.OutputFieldType
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.VersionRef
import promptengine.domain.render.OutputFormat
import promptengine.domain.shared.Cost
import promptengine.domain.shared.SemVer
import promptengine.domain.shared.TokenCount
import java.math.BigDecimal

class PipelineRequestFactoryTest {
    private val modelProfile = ModelProfile(TokenCount(4000), "tokenizer-a", Cost(BigDecimal("0.001")))
    private val factory = PipelineRequestFactory(modelProfile)

    private fun minimalInput(
        promptKey: String = "support/faq-answer",
        versionRef: String = "latest",
    ) = PipelineRequestInput(promptKey = promptKey, versionRef = versionRef, budget = 1000)

    @Test
    fun `createCompileCommand はtraceId-idempotencyKeyとPipelineRequestを保持する`() {
        val command = factory.createCompileCommand(minimalInput(), "trace-1", "idem-1")

        command.traceId shouldBe "trace-1"
        command.idempotencyKey shouldBe "idem-1"
        command.request.promptKey shouldBe PromptKey("support/faq-answer")
        command.request.modelProfile shouldBe modelProfile
    }

    @Test
    fun `createRenderCommand はPipelineRequestを構築する`() {
        val command = factory.createRenderCommand(minimalInput(), "trace-1", null)

        command.idempotencyKey shouldBe null
        command.request.budget shouldBe TokenCount(1000)
    }

    @Test
    fun `createExecuteCommand はPipelineRequestを構築する`() {
        val command = factory.createExecuteCommand(minimalInput(), "trace-1", "idem-2")

        command.request.versionRef shouldBe VersionRef.Latest
    }

    @Test
    fun `versionRef はlatest以外にsemVer形式とalias形式も変換する`() {
        factory.createCompileCommand(minimalInput(versionRef = "1.2.3"), "t", null).request.versionRef shouldBe
            VersionRef.Fixed(SemVer(1, 2, 3))
        factory.createCompileCommand(minimalInput(versionRef = "stable"), "t", null).request.versionRef shouldBe
            VersionRef.Alias("stable")
    }

    @Test
    fun `variableResolution は各マップからnull値を除去する`() {
        val input =
            minimalInput().copy(
                explicitParameters = mapOf("a" to "1", "b" to null),
                userVariables = mapOf("u" to null),
                workflowVariables = mapOf("w" to 2),
                environmentVariables = mapOf("e" to null),
                contextData = mapOf("conversation" to mapOf("topic" to "x", "ignored" to null)),
            )

        val request = factory.createCompileCommand(input, "t", null).request

        request.variableResolution.explicitParameters shouldBe mapOf("a" to "1")
        request.variableResolution.userVariables shouldBe emptyMap()
        request.variableResolution.workflowVariables shouldBe mapOf("w" to 2)
        request.variableResolution.environmentVariables shouldBe emptyMap()
        request.variableResolution.contextData shouldBe mapOf("conversation" to mapOf("topic" to "x"))
    }

    @Test
    fun `outputFormat-outputSchema-executionPolicyがnullなら未指定のまま変換する`() {
        val request = factory.createCompileCommand(minimalInput(), "t", null).request

        request.outputFormat shouldBe null
        request.outputSchema shouldBe null
        request.executionPolicy shouldBe null
    }

    @Test
    fun `outputFormat-outputSchemaを指定するとdomain型へ変換する`() {
        val input =
            minimalInput().copy(
                outputFormat = "JSON",
                outputSchema = OutputSchemaInput("schemas/x", listOf(OutputSchemaFieldInput("name", "STRING", true))),
            )

        val request = factory.createCompileCommand(input, "t", null).request

        request.outputFormat shouldBe OutputFormat.JSON
        request.outputSchema?.id shouldBe "schemas/x"
        request.outputSchema?.fields shouldBe
            listOf(promptengine.domain.parsing.OutputSchemaField("name", OutputFieldType.STRING, true))
    }

    @Test
    fun `executionPolicyはmaxRetries指定時にそのまま変換する`() {
        val input =
            minimalInput().copy(
                executionPolicy = ExecutionPolicyInput(timeoutMs = 5000, maxRetries = 3, parseRepair = true),
            )

        val request = factory.createCompileCommand(input, "t", null).request

        request.executionPolicy?.timeoutMs shouldBe 5000
        request.executionPolicy?.maxRetries shouldBe 3
        request.executionPolicy?.parseRepair?.enabled shouldBe true
    }

    @Test
    fun `executionPolicyはmaxRetries未指定時にdefaultMaxRetriesへフォールバックする`() {
        val input = minimalInput().copy(executionPolicy = ExecutionPolicyInput(timeoutMs = 5000, maxRetries = null))

        val request = factory.createCompileCommand(input, "t", null).request

        request.executionPolicy?.maxRetries shouldBe 2
    }
}
