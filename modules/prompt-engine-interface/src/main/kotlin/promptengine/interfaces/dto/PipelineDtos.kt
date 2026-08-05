package promptengine.interfaces.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank

/** [ExecutionPolicyDto.timeoutMs]の許容上限（ミリ秒、5分）。無制限だと1リクエストが実行スレッドを長時間占有し得るため。 */
private const val MAX_TIMEOUT_MS = 300_000L

/** [ExecutionPolicyDto.maxRetries]の許容上限。無制限だとAPAP呼出コスト・レイテンシが際限なく積み上がるため。 */
private const val MAX_RETRIES = 10

/** [OptionsDto.tokenBudget]の許容上限。極端な値を早期に弾くための緩い上限（正当な大規模コンテキスト用途は許容）。 */
private const val MAX_TOKEN_BUDGET = 1_000_000

data class OptionsDto(
    val optimize: Boolean = true,
    @field:Min(1) @field:Max(MAX_TOKEN_BUDGET.toLong()) val tokenBudget: Int? = null,
)

data class RenderRequestDto(
    @field:NotBlank val versionRef: String = "latest",
    val parameters: Map<String, Any?> = emptyMap(),
    val context: Map<String, Map<String, Any?>> = emptyMap(),
    @field:NotBlank val modelProfile: String,
    @field:Valid val options: OptionsDto? = null,
)

data class OutputSchemaFieldDto(val name: String, val type: String, val required: Boolean = false)

data class OutputSchemaDto(val id: String, val fields: List<OutputSchemaFieldDto> = emptyList())

data class ExecutionPolicyDto(
    @field:Min(1) @field:Max(MAX_TIMEOUT_MS) val timeoutMs: Long,
    @field:Min(0) @field:Max(MAX_RETRIES.toLong()) val maxRetries: Int? = null,
    val parseRepair: Boolean = false,
)

data class ExecuteRequestDto(
    @field:NotBlank val versionRef: String = "latest",
    val parameters: Map<String, Any?> = emptyMap(),
    val context: Map<String, Map<String, Any?>> = emptyMap(),
    @field:NotBlank val modelProfile: String,
    @field:Valid val options: OptionsDto? = null,
    @field:Valid val executionPolicy: ExecutionPolicyDto,
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
