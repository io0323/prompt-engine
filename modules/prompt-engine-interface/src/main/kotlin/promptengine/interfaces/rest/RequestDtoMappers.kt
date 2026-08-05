package promptengine.interfaces.rest

import promptengine.application.view.ContextRequirementInput
import promptengine.application.view.OutputDeclarationInput
import promptengine.application.view.ValidationSettingsInput
import promptengine.application.view.VariableDefinitionInput
import promptengine.interfaces.dto.ContextRequirementDto
import promptengine.interfaces.dto.OutputDeclarationDto
import promptengine.interfaces.dto.ValidationSettingsDto
import promptengine.interfaces.dto.VariableDefinitionDto

/**
 * リクエストDTO→application層Inputへの変換関数群（P9c）。プリミティブ型同士の1:1マッピング
 * のみで、domain型は一切現れない（[PromptViews.kt][promptengine.application.view]のKDoc参照。
 * detekt TooManyFunctions閾値対策で[ResponseDtoMappers.kt]と分割）。
 *
 * [VariableDefinitionDto.toInput]は[VariableDefinitionDto]を[VariableDefinitionInput]へ変換する。
 */
fun VariableDefinitionDto.toInput(): VariableDefinitionInput =
    VariableDefinitionInput(name, type, source, required, default, constraints, sensitive)

/** [ContextRequirementDto]を[ContextRequirementInput]へ変換する。 */
fun ContextRequirementDto.toInput(): ContextRequirementInput = ContextRequirementInput(scope, required, optional)

/** [ValidationSettingsDto]を[ValidationSettingsInput]へ変換する。 */
fun ValidationSettingsDto.toInput(): ValidationSettingsInput =
    ValidationSettingsInput(maxLength, maxTokens, policies, placeholders)

/** [OutputDeclarationDto]を[OutputDeclarationInput]へ変換する。 */
fun OutputDeclarationDto.toInput(): OutputDeclarationInput = OutputDeclarationInput(format, schemaRef)
