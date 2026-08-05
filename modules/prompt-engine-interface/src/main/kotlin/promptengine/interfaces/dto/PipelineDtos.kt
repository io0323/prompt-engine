package promptengine.interfaces.dto

import jakarta.validation.constraints.NotBlank

data class OptionsDto(val optimize: Boolean = true, val tokenBudget: Int? = null)

data class RenderRequestDto(
    @field:NotBlank val versionRef: String = "latest",
    val parameters: Map<String, Any?> = emptyMap(),
    val context: Map<String, Map<String, Any?>> = emptyMap(),
    @field:NotBlank val modelProfile: String,
    val options: OptionsDto? = null,
)

data class OutputSchemaFieldDto(val name: String, val type: String, val required: Boolean = false)

data class OutputSchemaDto(val id: String, val fields: List<OutputSchemaFieldDto> = emptyList())

data class ExecutionPolicyDto(
    val timeoutMs: Long,
    val maxRetries: Int? = null,
    val parseRepair: Boolean = false,
)

data class ExecuteRequestDto(
    @field:NotBlank val versionRef: String = "latest",
    val parameters: Map<String, Any?> = emptyMap(),
    val context: Map<String, Map<String, Any?>> = emptyMap(),
    @field:NotBlank val modelProfile: String,
    val options: OptionsDto? = null,
    val executionPolicy: ExecutionPolicyDto,
    val outputSchema: OutputSchemaDto? = null,
)

data class RenderedMessageDto(val role: String, val content: String)

data class FindingDto(val rule: String, val path: String, val severity: String, val message: String)

/** 設計書§13.2 `POST /prompts/{key}/render`のレスポンス例と1:1対応する。 */
data class RenderResponseDto(
    val promptKey: String,
    val version: String?,
    val messages: List<RenderedMessageDto>,
    val outputFormat: String?,
    val outputSchemaRef: String?,
    val tokenEstimate: Int?,
    val renderHash: String?,
    val warnings: List<FindingDto>,
    val traceId: String,
)

data class CompileResponseDto(
    val promptKey: String,
    val traceId: String,
    val validationPassed: Boolean,
    val warningCount: Int,
)

data class UsageDto(val inputTokens: Int, val outputTokens: Int, val cost: java.math.BigDecimal)

/** 設計書§13.2 `POST /prompts/{key}/execute`のレスポンス（render+以下のフィールド）と1:1対応する。 */
data class ExecuteResponseDto(
    val promptKey: String,
    val version: String?,
    val outputFormat: String?,
    val parsedOutput: Map<String, Any?>,
    val rawContent: String,
    val usage: UsageDto?,
    val latencyMs: Long?,
    val evaluationId: String?,
    val traceId: String,
)
