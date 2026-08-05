package promptengine.interfaces.dto

/** 検索系エンドポイントのページング応答（設計書§13共通仕様、既定20・上限100）。 */
data class PageDto<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
)

data class VariableDefinitionDto(
    val name: String,
    val type: String,
    val source: String = "STATIC",
    val required: Boolean = false,
    val default: Any? = null,
    val constraints: List<String> = emptyList(),
    val sensitive: Boolean = false,
)

data class ContextRequirementDto(
    val scope: String,
    val required: List<String> = emptyList(),
    val optional: List<String> = emptyList(),
)

data class ValidationSettingsDto(
    val maxLength: Int? = null,
    val maxTokens: Int? = null,
    val policies: List<String> = emptyList(),
    val placeholders: String = "LENIENT",
)

data class OutputDeclarationDto(val format: String, val schemaRef: String? = null)

data class ExtendsRefDto(val key: String, val range: String?)

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
