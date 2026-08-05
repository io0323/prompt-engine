package promptengine.interfaces.dto

/**
 * ページングクエリパラメータの`size`上限（設計書§13共通仕様「ページング（既定20・上限100）」）。
 * `promptengine.domain.shared.Page.MAX_SIZE`と同値。`prompt-engine-interface`はdomain層へ
 * 依存できない（ArchUnitルール、[promptengine.application.view.PromptViews.kt]のKDoc参照）ため、
 * この定数を独立して持つ（各`*QueryParams`の`@field:Max`から参照する）。
 */
internal const val MAX_PAGE_SIZE = 100L

/** 検索系エンドポイントのページング応答（設計書§13共通仕様、既定20・上限100）。 */
data class PageDto<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
)

/** `VariableDefinition`（設計書§4.4）のJSON表現。 */
data class VariableDefinitionDto(
    val name: String,
    val type: String,
    val source: String = "STATIC",
    val required: Boolean = false,
    val default: Any? = null,
    val constraints: List<String> = emptyList(),
    val sensitive: Boolean = false,
)

/** `ContextRequirement`（設計書§2.7）のJSON表現。 */
data class ContextRequirementDto(
    val scope: String,
    val required: List<String> = emptyList(),
    val optional: List<String> = emptyList(),
)

/** `ValidationSettings`（設計書§2.10）のJSON表現。 */
data class ValidationSettingsDto(
    val maxLength: Int? = null,
    val maxTokens: Int? = null,
    val policies: List<String> = emptyList(),
    val placeholders: String = "LENIENT",
)

/** `OutputDeclaration`（設計書§15.1 `output:`ブロック）のJSON表現。 */
data class OutputDeclarationDto(val format: String, val schemaRef: String? = null)

/** `ExtendsRef`（設計書§4.4、テンプレート継承先の参照）のJSON表現。 */
data class ExtendsRefDto(val key: String, val range: String?)

/** `PromptVersion`（設計書§4.4）のJSON表現。`GET /prompts/{namespace}/{name}`等のレスポンスに使う。 */
data class PromptVersionDto(
    val semVer: String,
    val state: String,
    val source: String,
    val contentHash: String,
    val variables: List<VariableDefinitionDto>,
    val contextRequirements: List<ContextRequirementDto>,
    val extends: ExtendsRefDto?,
    val validation: ValidationSettingsDto,
    val output: OutputDeclarationDto?,
)
