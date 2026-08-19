package promptengine.interfaces.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty

/**
 * [GoldenDatasetItemInputDto.parameters]/[GoldenDatasetItemInputDto.context]（設計書§12
 * `golden_dataset_items` JSONB）は値の型を制約しないため`Map<String, Any?>`で表す。
 *
 * 裸の`Map<String, Any?>`をそのまま公開すると、springdocのバージョンによって生成される
 * OpenAPIスキーマが`type: object`と`type: "null"`の間で揺れる（Issue #113。
 * `VariableDefinitionDto.default`・`PipelineDtos.kt`の`parameters`/`context`が抱える
 * 既知の未修正の問題と同じ）。ここでは`@Schema(type = "object")`で明示的にスキーマを固定し、
 * springdocの型推論に依存しないようにする（Issue #113の初適用、生成結果は
 * `contract`テストで固定する）。
 */
private const val OPEN_OBJECT_SCHEMA_DESCRIPTION = "任意のキー・値（JSONB、値の型は制約しない）"

/** [CreateGoldenDatasetRequestDto.items]の1件（設計書§12 `golden_dataset_items`、ADR-0035）。 */
data class GoldenDatasetItemInputDto(
    @field:Schema(type = "object", description = OPEN_OBJECT_SCHEMA_DESCRIPTION)
    val parameters: Map<String, Any?> = emptyMap(),
    @field:Schema(type = "object", description = "スコープ名→$OPEN_OBJECT_SCHEMA_DESCRIPTION")
    val context: Map<String, Map<String, Any?>> = emptyMap(),
    val expectedOutput: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

/** `POST /datasets`（設計書§13.1、ADR-0035決定2）のリクエストボディ。 */
data class CreateGoldenDatasetRequestDto(
    @field:NotBlank val promptKey: String,
    @field:NotBlank val name: String,
    val description: String? = null,
    @field:Valid @field:NotEmpty val items: List<GoldenDatasetItemInputDto>,
)

data class CreateGoldenDatasetResponseDto(val datasetId: String, val promptKey: String, val itemCount: Int)

/** `GET /datasets/{id}`の1項目分。 */
data class GoldenDatasetItemDto(
    val itemId: String,
    @field:Schema(type = "object", description = OPEN_OBJECT_SCHEMA_DESCRIPTION)
    val parameters: Map<String, Any?>,
    @field:Schema(type = "object", description = "スコープ名→$OPEN_OBJECT_SCHEMA_DESCRIPTION")
    val context: Map<String, Map<String, Any?>>,
    val expectedOutput: String?,
    val metadata: Map<String, String>,
)

/** `GET /datasets/{id}`のレスポンス。 */
data class GoldenDatasetResponseDto(
    val datasetId: String,
    val promptKey: String,
    val name: String,
    val description: String?,
    val items: List<GoldenDatasetItemDto>,
)
