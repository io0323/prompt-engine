package promptengine.application.view

import promptengine.domain.context.ContextRequirement
import promptengine.domain.prompt.LifecycleState
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.VersionRef
import promptengine.domain.render.OutputDeclaration
import promptengine.domain.render.OutputFormat
import promptengine.domain.shared.SemVer
import promptengine.domain.validation.PlaceholderMode
import promptengine.domain.validation.ValidationSettings
import promptengine.domain.variable.VariableDefinition
import promptengine.domain.variable.VariableSource
import promptengine.domain.variable.VariableType

private const val SEMVER_PARTS_COUNT = 3

/**
 * リクエストDTOのプリミティブ値からdomain値オブジェクトを構築する変換点（P9c）。
 *
 * `prompt-engine-interface`は`promptengine.domain..`に依存できない（ArchUnitルール、
 * [PromptViews.kt][promptengine.application.view]のKDoc参照）ため、`PromptKey`/`SemVer`等の
 * domain型を直接構築できない。本Objectがその構築を`promptengine.application`側で肩代わりし、
 * [PromptCommandFactory]・[VersionCommandFactory]・[QueryFactory]から共通利用される。
 */
object DomainValueFactory {
    /** `text`から`PromptKey`を構築する。不正な形式は`PromptKey`の`init`が投げる`IllegalArgumentException`をそのまま伝播させる。 */
    fun promptKey(text: String): PromptKey = PromptKey(text)

    /**
     * `namespace`・`name`（それぞれ`/api/v1/prompts/{namespace}/{name}/...`の個別パス変数、
     * 9c）を結合した`PromptKey`文字列表現に復元する唯一の場所（[PromptKey]の`namespace/name`
     * ちょうど2セグメント制約、ADR-0023参照）。Controllerはこの関数を経由するのみとし、
     * `"$namespace/$name"`を自前で組み立てない。不正な形式（空文字・大文字・記号等）は
     * [PromptKey]の`init`が投げる`IllegalArgumentException`をそのまま伝播させ、
     * `GlobalExceptionHandler`の既存ハンドラ（`INVALID_REQUEST`、400）に委ねる
     * （新規エラーコードは追加しない）。
     */
    fun promptKeyText(
        namespace: String,
        name: String,
    ): String = promptKey("$namespace/$name").value

    /** `"major.minor.patch"`形式の文字列から`SemVer`を構築する。形式が異なれば`IllegalArgumentException`。 */
    fun semVer(text: String): SemVer {
        val parts = text.split(".")
        require(parts.size == SEMVER_PARTS_COUNT) { "invalid semVer format (expected major.minor.patch): $text" }
        return SemVer(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
    }

    /** `"latest"`（大小無視）/SemVer形式/それ以外（Alias名）を判別して`VersionRef`を構築する。 */
    fun versionRef(text: String): VersionRef =
        when {
            text.equals("latest", ignoreCase = true) -> VersionRef.Latest
            SEMVER_PATTERN.matches(text) -> VersionRef.Fixed(semVer(text))
            else -> VersionRef.Alias(text)
        }

    /** `LifecycleState`の名前（`Draft`等）から`LifecycleState`を構築する。未知の値は`IllegalArgumentException`。 */
    fun lifecycleState(text: String): LifecycleState =
        when (text) {
            "Draft" -> LifecycleState.Draft
            "InReview" -> LifecycleState.InReview
            "Approved" -> LifecycleState.Approved
            "Published" -> LifecycleState.Published
            "Deprecated" -> LifecycleState.Deprecated
            "Archived" -> LifecycleState.Archived
            else -> throw IllegalArgumentException("invalid status: $text")
        }

    /** [VariableDefinitionInput]をdomain型`VariableDefinition`へ変換する。 */
    fun variableDefinition(input: VariableDefinitionInput): VariableDefinition =
        VariableDefinition(
            name = input.name,
            type = VariableType.valueOf(input.type),
            source = VariableSource.valueOf(input.source),
            required = input.required,
            default = input.default,
            constraints = input.constraints,
            sensitive = input.sensitive,
        )

    /** [ContextRequirementInput]をdomain型`ContextRequirement`へ変換する。 */
    fun contextRequirement(input: ContextRequirementInput): ContextRequirement =
        ContextRequirement(input.scope, input.required, input.optional)

    /** [ValidationSettingsInput]をdomain型`ValidationSettings`へ変換する。`null`なら既定値。 */
    fun validationSettings(input: ValidationSettingsInput?): ValidationSettings =
        input?.let {
            ValidationSettings(it.maxLength, it.maxTokens, it.policies, PlaceholderMode.valueOf(it.placeholders))
        } ?: ValidationSettings()

    /** [OutputDeclarationInput]をdomain型`OutputDeclaration`へ変換する。`null`なら`null`。 */
    fun outputDeclaration(input: OutputDeclarationInput?): OutputDeclaration? =
        input?.let { OutputDeclaration(OutputFormat.valueOf(it.format), it.schemaRef) }

    private val SEMVER_PATTERN = Regex("^\\d+\\.\\d+\\.\\d+$")
}
