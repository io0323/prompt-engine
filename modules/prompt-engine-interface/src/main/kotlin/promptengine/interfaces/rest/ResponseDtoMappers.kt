package promptengine.interfaces.rest

import promptengine.application.view.ContextRequirementView
import promptengine.application.view.OutputDeclarationView
import promptengine.application.view.PromptMetadataView
import promptengine.application.view.PromptSummaryView
import promptengine.application.view.PromptVersionView
import promptengine.application.view.ValidationSettingsView
import promptengine.application.view.VariableDefinitionView
import promptengine.interfaces.dto.ContextRequirementDto
import promptengine.interfaces.dto.ExtendsRefDto
import promptengine.interfaces.dto.OutputDeclarationDto
import promptengine.interfaces.dto.PromptMetadataDto
import promptengine.interfaces.dto.PromptSummaryDto
import promptengine.interfaces.dto.PromptVersionDto
import promptengine.interfaces.dto.ValidationSettingsDto
import promptengine.interfaces.dto.VariableDefinitionDto

/**
 * application層View→レスポンスDTOへの変換関数群（P9c、[RequestDtoMappers.kt]のKDoc参照）。
 */
fun VariableDefinitionView.toDto(): VariableDefinitionDto =
    VariableDefinitionDto(name, type, source, required, default, constraints, sensitive)

fun ContextRequirementView.toDto(): ContextRequirementDto = ContextRequirementDto(scope, required, optional)

fun ValidationSettingsView.toDto(): ValidationSettingsDto =
    ValidationSettingsDto(maxLength, maxTokens, policies, placeholders)

fun OutputDeclarationView.toDto(): OutputDeclarationDto = OutputDeclarationDto(format, schemaRef)

fun PromptMetadataView.toDto(): PromptMetadataDto = PromptMetadataDto(key, name, category, description, tags)

fun PromptVersionView.toDto(): PromptVersionDto =
    PromptVersionDto(
        semVer = semVer,
        state = state,
        source = source,
        contentHash = contentHash,
        variables = variables.map { it.toDto() },
        contextRequirements = contextRequirements.map { it.toDto() },
        extends = extends?.let { ExtendsRefDto(it.key, it.range) },
        validation = validation.toDto(),
        output = output?.toDto(),
    )

fun PromptSummaryView.toDto(): PromptSummaryDto =
    PromptSummaryDto(key, name, category, tags, status, latestVersion, publishedVersion)
