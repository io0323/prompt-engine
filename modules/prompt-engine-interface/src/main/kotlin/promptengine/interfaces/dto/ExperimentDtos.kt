package promptengine.interfaces.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty

/** [CreateExperimentRequestDto.variants]の1件（設計書§13.1 `POST /experiments`）。 */
data class VariantInputDto(
    @field:NotBlank val name: String,
    @field:NotBlank val semVer: String,
    val weightPct: Int,
)

/** `POST /experiments`（設計書§13.1、ADR-0034）のリクエストボディ。 */
data class CreateExperimentRequestDto(
    @field:NotBlank val promptKey: String,
    @field:NotBlank val type: String,
    @field:Valid @field:NotEmpty val variants: List<VariantInputDto>,
    val stickyKeyPath: String? = null,
)

data class ExperimentResponseDto(val experimentId: String, val promptKey: String)

/** [UpdateExperimentTrafficRequestDto.weights]の1件。 */
data class VariantWeightInputDto(
    @field:NotBlank val name: String,
    val weightPct: Int,
)

/** `PATCH /experiments/{id}/traffic`（設計書§13.1、ADR-0034決定6）のリクエストボディ。 */
data class UpdateExperimentTrafficRequestDto(
    @field:Valid @field:NotEmpty val weights: List<VariantWeightInputDto>,
)

/** `POST /experiments/{id}/promote`（設計書§13.1、ADR-0034決定5）のリクエストボディ。 */
data class PromoteExperimentRequestDto(
    @field:NotBlank val winnerVariantName: String,
)

data class PromoteExperimentResponseDto(val experimentId: String, val promptKey: String, val promotedSemVer: String)

/** `GET /experiments/{id}/results`の1 Variant分（設計書§13.1）。 */
data class VariantResultDto(val variantName: String, val weightPct: Int, val isControl: Boolean)

/** `GET /experiments/{id}/results`の1指標・1Variant分の統計判定。 */
data class MetricJudgmentDto(
    val metricType: String,
    val challengerVariantName: String,
    val controlSampleSize: Int,
    val challengerSampleSize: Int,
    val controlMean: String?,
    val challengerMean: String?,
    val pValue: Double?,
    val sufficientSample: Boolean,
    val significant: Boolean,
)

data class ExperimentResultsResponseDto(
    val experimentId: String,
    val promptKey: String,
    val status: String,
    val controlVariantName: String,
    val variants: List<VariantResultDto>,
    val judgments: List<MetricJudgmentDto>,
)
