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
 *
 * [RequestMeta]はリクエスト共通のメタ情報（`actor`はJWT `sub`由来、`traceId`は伝播用、
 * `idempotencyKey`は任意）。
 */
data class RequestMeta(val actor: String, val traceId: String, val idempotencyKey: String?)

/** `POST /prompts`のリクエストボディのうち、Variable/ContextRequirement/Validation/Output以外の中核フィールド。 */
data class CreatePromptCoreInput(
    val key: String,
    val name: String,
    val category: String?,
    val description: String?,
    val tags: List<String>,
    val semVer: String,
    val source: String,
)

/** `POST /prompts/{namespace}/{name}/versions`のリクエストボディのうち中核フィールド（[CreatePromptCoreInput]の要領）。 */
data class CreateVersionCoreInput(val key: String, val semVer: String, val source: String)

/** Prompt/Version作成時のVariable/ContextRequirement/Validation/Outputをまとめた入力（P9c）。 */
data class PromptVersionContentInput(
    val variables: List<VariableDefinitionInput> = emptyList(),
    val contextRequirements: List<ContextRequirementInput> = emptyList(),
    val validation: ValidationSettingsInput? = null,
    val output: OutputDeclarationInput? = null,
)

/** `PATCH /prompts/{namespace}/{name}`のリクエストボディ。 */
data class UpdatePromptMetadataInput(
    val name: String,
    val category: String?,
    val description: String?,
    val tags: List<String>,
)

/** `GET /prompts`検索の絞り込み条件（クエリパラメータ由来）。 */
data class SearchFiltersInput(
    val q: String? = null,
    val tag: String? = null,
    val category: String? = null,
    val status: String? = null,
)

/** 監査ログ・メトリクス検索の期間絞り込み（`from`/`to`とも任意）。 */
data class TimeRange(val from: Instant?, val to: Instant?)

/** ページング指定（設計書§13共通仕様、既定20・上限100はController側のバリデーションで担保）。 */
data class Paging(val page: Int, val size: Int)

/** `VariableDefinitionDto`の入力表現（[DomainValueFactory.variableDefinition]がdomain型へ変換する）。 */
data class VariableDefinitionInput(
    val name: String,
    val type: String,
    val source: String = "STATIC",
    val required: Boolean = false,
    val default: Any? = null,
    val constraints: List<String> = emptyList(),
    val sensitive: Boolean = false,
)

/** `ContextRequirementDto`の入力表現（[promptengine.application.view.DomainValueFactory.contextRequirement]参照）。 */
data class ContextRequirementInput(
    val scope: String,
    val required: List<String> = emptyList(),
    val optional: List<String> = emptyList(),
)

/** `ValidationSettingsDto`の入力表現（[promptengine.application.view.DomainValueFactory.validationSettings]参照）。 */
data class ValidationSettingsInput(
    val maxLength: Int? = null,
    val maxTokens: Int? = null,
    val policies: List<String> = emptyList(),
    val placeholders: String = "LENIENT",
)

/** `OutputDeclarationDto`の入力表現（[promptengine.application.view.DomainValueFactory.outputDeclaration]参照）。 */
data class OutputDeclarationInput(val format: String, val schemaRef: String? = null)

/** `OutputSchemaInput.fields`の1件（`POST /execute`が任意で渡す構造化出力Schemaのフィールド定義）。 */
data class OutputSchemaFieldInput(val name: String, val type: String, val required: Boolean = false)

/** `POST /execute`が任意で渡すStructured Output検証用Schema（ADR-0022、M1では`SchemaRepository`経由の解決を行わない）。 */
data class OutputSchemaInput(val id: String, val fields: List<OutputSchemaFieldInput> = emptyList())

/** `POST /execute`の`executionPolicy`の入力表現。 */
data class ExecutionPolicyInput(
    val timeoutMs: Long,
    val maxRetries: Int? = null,
    val parseRepair: Boolean = false,
)
