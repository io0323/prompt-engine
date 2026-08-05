package promptengine.interfaces.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank

/** `POST /prompts`（設計書§13.1・§13.2）のリクエストボディ。初版Version込みでPromptを作成する。 */
data class CreatePromptRequestDto(
    @field:NotBlank val key: String,
    @field:NotBlank val name: String,
    val category: String? = null,
    val description: String? = null,
    val tags: List<String> = emptyList(),
    @field:NotBlank val semVer: String,
    @field:NotBlank val source: String,
    @field:Valid val variables: List<VariableDefinitionDto> = emptyList(),
    @field:Valid val contextRequirements: List<ContextRequirementDto> = emptyList(),
    @field:Valid val validation: ValidationSettingsDto? = null,
    @field:Valid val output: OutputDeclarationDto? = null,
)

/** `PATCH /prompts/{namespace}/{name}`（設計書§13.1）のリクエストボディ。 */
data class UpdatePromptMetadataRequestDto(
    @field:NotBlank val name: String,
    val category: String? = null,
    val description: String? = null,
    val tags: List<String> = emptyList(),
)

/** `POST /prompts/{namespace}/{name}/versions`（設計書§13.1）のリクエストボディ。 */
data class CreateVersionRequestDto(
    @field:NotBlank val semVer: String,
    @field:NotBlank val source: String,
    @field:Valid val variables: List<VariableDefinitionDto> = emptyList(),
    @field:Valid val contextRequirements: List<ContextRequirementDto> = emptyList(),
    @field:Valid val validation: ValidationSettingsDto? = null,
    @field:Valid val output: OutputDeclarationDto? = null,
)

/** `POST /prompts/{namespace}/{name}/rollback`（設計書§13.1）のリクエストボディ。 */
data class RollbackRequestDto(
    @field:NotBlank val targetVersion: String,
)

/** `POST /prompts/{namespace}/{name}/versions/{version}/deprecate`（設計書§13.1）のリクエストボディ。 */
data class DeprecateRequestDto(val recommendedReplacement: String? = null)

/** `POST /prompts/{namespace}/{name}/aliases`（設計書§13.1）のリクエストボディ。 */
data class SetAliasRequestDto(
    @field:NotBlank val alias: String,
    @field:NotBlank val version: String,
)

/** `PromptMetadata`（設計書§4.4）のJSON表現。 */
data class PromptMetadataDto(
    val key: String,
    val name: String,
    val category: String?,
    val description: String?,
    val tags: List<String>,
)

/** `GET /prompts/{namespace}/{name}`（設計書§13.1・§13.2）のレスポンス。 */
data class PromptDetailDto(val metadata: PromptMetadataDto?, val versions: List<PromptVersionDto>)

/**
 * `GET /prompts`のクエリパラメータ（設計書§13.1）を`@ModelAttribute`でまとめて受け取る
 * （detekt LongParameterList対策。全フィールドが`@RequestParam`由来のため、Spring MVCの
 * command object bindingで個別`@RequestParam`宣言と同じ挙動になる）。
 */
data class PromptSearchQueryParams(
    val q: String? = null,
    val tag: String? = null,
    val category: String? = null,
    val status: String? = null,
    @field:Min(0) val page: Int = 0,
    @field:Min(1) @field:Max(MAX_PAGE_SIZE) val size: Int = 20,
)

/** `PromptSummary`（設計書§4.4、`GET /prompts`検索結果の1行）のJSON表現。 */
data class PromptSummaryDto(
    val key: String,
    val name: String,
    val category: String?,
    val tags: List<String>,
    val status: String,
    val latestVersion: String,
    val publishedVersion: String?,
)

/** `GET /prompts/{namespace}/{name}/diff`（設計書§13.1・§13.2）のレスポンス。 */
data class DiffResponseDto(
    val key: String,
    val from: String,
    val to: String,
    val contentChanged: Boolean,
    val fromContentHash: String,
    val toContentHash: String,
    val variablesChanged: Boolean,
    val contextRequirementsChanged: Boolean,
    val extendsChanged: Boolean,
    val validationChanged: Boolean,
    val outputChanged: Boolean,
)

/** `DependencyEdge`（設計書§4.4、`GET /prompts/{namespace}/{name}/dependencies`結果の1行）のJSON表現。 */
data class DependencyEdgeDto(
    val fromKey: String,
    val fromVersion: String,
    val toKind: String,
    val toKey: String,
    val toVersion: String?,
)

/** `key`/`semVer`のみを返すシンプルなレスポンス（Prompt作成・Version作成・publish等）。 */
data class KeySemVerResponseDto(val key: String, val semVer: String)

/** `POST /prompts/{namespace}/{name}/rollback`（設計書§13.1・§13.2）のレスポンス。 */
data class RollbackResponseDto(val key: String, val targetSemVer: String)

/** `POST /prompts/{namespace}/{name}/aliases`（設計書§13.1・§13.2）のレスポンス。 */
data class SetAliasResponseDto(val key: String, val alias: String, val semVer: String)

/** `key`のみを返すシンプルなレスポンス（メタデータ更新等）。 */
data class KeyOnlyResponseDto(val key: String)
