package promptengine.application.view

import java.time.Instant

/**
 * `prompt-engine-interface`のリクエストDTOからCommand/Query構築関数
 * （[PromptCommandFactory]・[VersionCommandFactory]・[QueryFactory]・
 * [promptengine.application.pipeline.PipelineRequestFactory]）へ渡す入力値（P9c）。
 * 全フィールドがプリミティブ型のみで構成される（[PromptViews.kt][promptengine.application.view]の
 * KDoc参照、ArchUnit境界維持のため）。
 *
 * [RequestMeta]・[CreatePromptCoreInput]・[CreateVersionCoreInput]・[PromptVersionContentInput]・
 * [UpdatePromptMetadataInput]・[SearchFiltersInput]・[TimeRange]・[Paging]は、Command/Query構築
 * 関数のパラメータ数をdetekt LongParameterList閾値（設定値は6だが、実際には6個以上で発火する
 * ため実質5個以下）に収めるためのグルーピングであり、DTOの構造そのものを表すものではない。
 */
data class RequestMeta(val actor: String, val traceId: String, val idempotencyKey: String?)

data class CreatePromptCoreInput(
    val key: String,
    val name: String,
    val category: String?,
    val description: String?,
    val tags: List<String>,
    val semVer: String,
    val source: String,
)

data class CreateVersionCoreInput(val key: String, val semVer: String, val source: String)

/** Prompt/Version作成時のVariable/ContextRequirement/Validation/Outputをまとめた入力（P9c）。 */
data class PromptVersionContentInput(
    val variables: List<VariableDefinitionInput> = emptyList(),
    val contextRequirements: List<ContextRequirementInput> = emptyList(),
    val validation: ValidationSettingsInput? = null,
    val output: OutputDeclarationInput? = null,
)

data class UpdatePromptMetadataInput(
    val name: String,
    val category: String?,
    val description: String?,
    val tags: List<String>,
)

data class SearchFiltersInput(
    val q: String? = null,
    val tag: String? = null,
    val category: String? = null,
    val status: String? = null,
)

data class TimeRange(val from: Instant?, val to: Instant?)

data class Paging(val page: Int, val size: Int)

data class VariableDefinitionInput(
    val name: String,
    val type: String,
    val source: String = "STATIC",
    val required: Boolean = false,
    val default: Any? = null,
    val constraints: List<String> = emptyList(),
    val sensitive: Boolean = false,
)

data class ContextRequirementInput(
    val scope: String,
    val required: List<String> = emptyList(),
    val optional: List<String> = emptyList(),
)

data class ValidationSettingsInput(
    val maxLength: Int? = null,
    val maxTokens: Int? = null,
    val policies: List<String> = emptyList(),
    val placeholders: String = "LENIENT",
)

data class OutputDeclarationInput(val format: String, val schemaRef: String? = null)

data class OutputSchemaFieldInput(val name: String, val type: String, val required: Boolean = false)

data class OutputSchemaInput(val id: String, val fields: List<OutputSchemaFieldInput> = emptyList())

data class ExecutionPolicyInput(
    val timeoutMs: Long,
    val maxRetries: Int? = null,
    val parseRepair: Boolean = false,
)
