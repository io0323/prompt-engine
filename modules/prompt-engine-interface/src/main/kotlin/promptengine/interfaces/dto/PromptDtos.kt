package promptengine.interfaces.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank

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

data class UpdatePromptMetadataRequestDto(
    @field:NotBlank val name: String,
    val category: String? = null,
    val description: String? = null,
    val tags: List<String> = emptyList(),
)

data class CreateVersionRequestDto(
    @field:NotBlank val semVer: String,
    @field:NotBlank val source: String,
    @field:Valid val variables: List<VariableDefinitionDto> = emptyList(),
    @field:Valid val contextRequirements: List<ContextRequirementDto> = emptyList(),
    @field:Valid val validation: ValidationSettingsDto? = null,
    @field:Valid val output: OutputDeclarationDto? = null,
)

data class RollbackRequestDto(
    @field:NotBlank val targetVersion: String,
)

data class DeprecateRequestDto(val recommendedReplacement: String? = null)

data class SetAliasRequestDto(
    @field:NotBlank val alias: String,
    @field:NotBlank val version: String,
)

data class PromptMetadataDto(
    val key: String,
    val name: String,
    val category: String?,
    val description: String?,
    val tags: List<String>,
)

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
    val page: Int = 0,
    val size: Int = 20,
)

data class PromptSummaryDto(
    val key: String,
    val name: String,
    val category: String?,
    val tags: List<String>,
    val status: String,
    val latestVersion: String,
    val publishedVersion: String?,
)

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

data class DependencyEdgeDto(
    val fromKey: String,
    val fromVersion: String,
    val toKind: String,
    val toKey: String,
    val toVersion: String?,
)

data class KeySemVerResponseDto(val key: String, val semVer: String)

data class RollbackResponseDto(val key: String, val targetSemVer: String)

data class SetAliasResponseDto(val key: String, val alias: String, val semVer: String)

data class KeyOnlyResponseDto(val key: String)
