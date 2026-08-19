package promptengine.interfaces.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Positive

/** [CreateBenchmarkRequestDto.targets]の1件（設計書§13.1 `POST /benchmarks`、ADR-0035）。 */
data class CreateBenchmarkTargetInputDto(
    @field:NotBlank val semVer: String,
)

/** `POST /benchmarks`（設計書§13.1、ADR-0035決定3・決定5）のリクエストボディ。 */
data class CreateBenchmarkRequestDto(
    @field:NotBlank val promptKey: String,
    @field:NotBlank val datasetId: String,
    @field:Valid @field:NotEmpty val targets: List<CreateBenchmarkTargetInputDto>,
    @field:NotEmpty val metrics: Set<String>,
    @field:Positive val nRepetitions: Int = DEFAULT_N_REPETITIONS,
    val temperature: Double? = null,
) {
    private companion object {
        /** ADR-0035決定5「Nの既定値」。 */
        const val DEFAULT_N_REPETITIONS = 3
    }
}

/** `POST /benchmarks`のレスポンス。`estimatedExecutionCount`はADR-0035決定5「事前コスト見積り」。 */
data class CreateBenchmarkResponseDto(
    val benchmarkId: String,
    val promptKey: String,
    val status: String,
    val estimatedExecutionCount: Int,
)

/** `GET /benchmarks/{id}`の1Target分。 */
data class BenchmarkTargetDto(val targetId: String, val semVer: String)

/** `GET /benchmarks/{id}`の進捗集計。`totalItems`は`estimatedExecutionCount`（実行回数）とは別単位。 */
data class BenchmarkProgressDto(
    val totalItems: Int,
    val completedItems: Int,
    val failedItems: Int,
    val pendingItems: Int,
)

/** `GET /benchmarks/{id}`のレスポンス（設計書§13.1「取得（進捗含む）」）。 */
data class BenchmarkResponseDto(
    val benchmarkId: String,
    val promptKey: String,
    val datasetId: String,
    val targets: List<BenchmarkTargetDto>,
    val metrics: Set<String>,
    val nRepetitions: Int,
    val temperature: Double?,
    val status: String,
    val estimatedExecutionCount: Int,
    val progress: BenchmarkProgressDto,
)

/** `POST /benchmarks/{id}/cancel`のレスポンス。 */
data class CancelBenchmarkResponseDto(val benchmarkId: String, val status: String)

/** `GET /benchmarks/{id}/results`の1項目分。スコアは`BigDecimal.toString()`（`controlMean`等と同じ扱い）。 */
data class BenchmarkItemResultDto(
    val targetId: String,
    val itemId: String,
    val status: String,
    val accuracyScore: String?,
    val consistencyScore: String?,
    val determinismScore: String?,
    val errorMessage: String?,
)

/** `GET /benchmarks/{id}/results`のレスポンス。 */
data class BenchmarkResultsResponseDto(
    val benchmarkId: String,
    val status: String,
    val items: List<BenchmarkItemResultDto>,
)
