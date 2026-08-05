package promptengine.application.pipeline

import promptengine.application.view.DomainValueFactory
import promptengine.application.view.ExecutionPolicyInput
import promptengine.application.view.OutputSchemaInput
import promptengine.domain.execution.ExecutionPolicy
import promptengine.domain.execution.ParseRepairPolicy
import promptengine.domain.optimization.ModelProfile
import promptengine.domain.parsing.OutputFieldType
import promptengine.domain.parsing.OutputSchema
import promptengine.domain.parsing.OutputSchemaField
import promptengine.domain.pipeline.PipelineRequest
import promptengine.domain.render.OutputFormat
import promptengine.domain.shared.PromptRequest
import promptengine.domain.shared.TokenCount

/**
 * `POST /prompts/{key}/compile|render|execute`のリクエストDTOから[CompileCommand]/
 * [RenderCommand]/[ExecuteCommand]を構築する（P9c）。
 *
 * `prompt-engine-interface`は[PipelineRequest]・[ModelProfile]（いずれもdomain型）を
 * 直接構築・保持できない（[PromptViews.kt][promptengine.application.view]のKDoc参照、ArchUnit境界）
 * ため、[PipelineRequest]自体をControllerへ返さず、`create*Command`が構築済みのCommand
 * （application層の型）まで組み立ててから返す。[modelProfile]はSpring Beanとして注入で受け取る。
 *
 * `modelProfile`（設計書§13.2「APAP登録プロファイル参照名」）の値自体は、実APAPアダプタ導入（M2）
 * まで実体を持たないため使用しない（`EngineConfig.defaultModelProfile`のKDoc参照）。
 * 非空検証のみ呼出元（Controller）が行う契約とする。
 */
class PipelineRequestFactory(private val modelProfile: ModelProfile) {
    /** `POST /prompts/{namespace}/{name}/compile`用の[CompileCommand]を構築する。 */
    fun createCompileCommand(
        input: PipelineRequestInput,
        traceId: String,
        idempotencyKey: String?,
    ): CompileCommand = CompileCommand(toPipelineRequest(input), traceId, idempotencyKey)

    /** `POST /prompts/{namespace}/{name}/render`用の[RenderCommand]を構築する。 */
    fun createRenderCommand(
        input: PipelineRequestInput,
        traceId: String,
        idempotencyKey: String?,
    ): RenderCommand = RenderCommand(toPipelineRequest(input), traceId, idempotencyKey)

    /** `POST /prompts/{namespace}/{name}/execute`用の[ExecuteCommand]を構築する。 */
    fun createExecuteCommand(
        input: PipelineRequestInput,
        traceId: String,
        idempotencyKey: String?,
    ): ExecuteCommand = ExecuteCommand(toPipelineRequest(input), traceId, idempotencyKey)

    private fun toPipelineRequest(input: PipelineRequestInput): PipelineRequest =
        PipelineRequest(
            promptKey = DomainValueFactory.promptKey(input.promptKey),
            versionRef = DomainValueFactory.versionRef(input.versionRef),
            variableResolution =
                PromptRequest(
                    explicitParameters = input.explicitParameters.filterNonNull(),
                    userVariables = input.userVariables.filterNonNull(),
                    workflowVariables = input.workflowVariables.filterNonNull(),
                    environmentVariables = input.environmentVariables.filterNonNull(),
                    contextData = input.contextData.mapValues { it.value.filterNonNull() },
                ),
            modelProfile = modelProfile,
            budget = TokenCount(input.budget),
            outputFormat = input.outputFormat?.let { OutputFormat.valueOf(it) },
            outputSchema = input.outputSchema?.let { toOutputSchema(it) },
            executionPolicy = input.executionPolicy?.let { toExecutionPolicy(it) },
        )

    private fun toOutputSchema(input: OutputSchemaInput): OutputSchema =
        OutputSchema(
            id = input.id,
            fields =
                input.fields.map {
                    OutputSchemaField(it.name, OutputFieldType.valueOf(it.type), it.required)
                },
        )

    private fun toExecutionPolicy(input: ExecutionPolicyInput): ExecutionPolicy {
        val parseRepair = ParseRepairPolicy(enabled = input.parseRepair)
        return input.maxRetries?.let { ExecutionPolicy(input.timeoutMs, it, parseRepair = parseRepair) }
            ?: ExecutionPolicy(input.timeoutMs, parseRepair = parseRepair)
    }

    private fun Map<String, Any?>.filterNonNull(): Map<String, Any> =
        mapNotNull { (key, value) -> value?.let { key to it } }.toMap()
}

/** [PipelineRequestFactory]への入力。全フィールドがプリミティブ型のみで構成される。 */
data class PipelineRequestInput(
    val promptKey: String,
    val versionRef: String,
    val explicitParameters: Map<String, Any?> = emptyMap(),
    val userVariables: Map<String, Any?> = emptyMap(),
    val workflowVariables: Map<String, Any?> = emptyMap(),
    val environmentVariables: Map<String, Any?> = emptyMap(),
    val contextData: Map<String, Map<String, Any?>> = emptyMap(),
    val budget: Int,
    val outputFormat: String? = null,
    val outputSchema: OutputSchemaInput? = null,
    val executionPolicy: ExecutionPolicyInput? = null,
)
