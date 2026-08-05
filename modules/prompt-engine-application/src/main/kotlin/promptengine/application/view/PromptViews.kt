package promptengine.application.view

import promptengine.application.query.GetPromptResult
import promptengine.domain.prompt.PromptMetadata
import promptengine.domain.prompt.PromptSummary
import promptengine.domain.prompt.PromptVersion
import promptengine.domain.prompt.PromptVersionDiff
import promptengine.domain.shared.Page
import promptengine.domain.variable.VariableDefinition

/**
 * `promptengine.domain..`型を`prompt-engine-interface`に一切露出させないための変換層（P9c）。
 *
 * ArchUnitルール「prompt-engine-interfaceはprompt-engine-applicationのみを呼びRepository実装に
 * 直接触れない」（`ArchitectureTest`、`prompt-engine-bootstrap`）は`promptengine.domain..`への
 * 依存も禁止する。Command/Queryハンドラの結果型（`CreatePromptResult`等）やQueryが返す
 * ドメインオブジェクト自体（`PromptVersion`・`Page<PromptSummary>`等）はdomain型のフィールドを
 * 持つため、Controllerがこれらのプロパティに直接アクセスするだけでバイトコード上の依存が
 * 生じ、ルールに抵触する。本ファイル群（[PromptViews]・[CommandResultViews]・
 * [DependencyMetricsAuditViews]、detekt TooManyFunctions閾値対策でファイル分割）は、値を
 * 保持するだけの型（プリミティブ型のみで構成したView）へすべて変換する関数群を提供する。
 * Controllerはこの変換後のView型のみを扱う。
 */
data class PageView<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
)

fun <T, R> Page<T>.toView(mapper: (T) -> R): PageView<R> = PageView(items.map(mapper), page, size, totalElements)

data class VariableDefinitionView(
    val name: String,
    val type: String,
    val source: String,
    val required: Boolean,
    val default: Any?,
    val constraints: List<String>,
    val sensitive: Boolean,
)

fun VariableDefinition.toView(): VariableDefinitionView =
    VariableDefinitionView(name, type.name, source.name, required, default, constraints, sensitive)

data class ContextRequirementView(val scope: String, val required: List<String>, val optional: List<String>)

data class ExtendsRefView(val key: String, val range: String?)

data class ValidationSettingsView(
    val maxLength: Int?,
    val maxTokens: Int?,
    val policies: List<String>,
    val placeholders: String,
)

data class OutputDeclarationView(val format: String, val schemaRef: String?)

data class PromptVersionView(
    val semVer: String,
    val state: String,
    val source: String,
    val contentHash: String,
    val variables: List<VariableDefinitionView>,
    val contextRequirements: List<ContextRequirementView>,
    val extends: ExtendsRefView?,
    val validation: ValidationSettingsView,
    val output: OutputDeclarationView?,
)

fun PromptVersion.toView(): PromptVersionView =
    PromptVersionView(
        semVer = semVer.toString(),
        state = state::class.simpleName ?: "Unknown",
        source = content.source,
        contentHash = content.contentHash,
        variables = variables.map { it.toView() },
        contextRequirements = contextRequirements.map { ContextRequirementView(it.scope, it.required, it.optional) },
        extends = extends?.let { ExtendsRefView(it.key.value, it.range.toRangeText()) },
        validation =
            ValidationSettingsView(
                validation.maxLength,
                validation.maxTokens,
                validation.policies,
                validation.placeholders.name,
            ),
        output = output?.let { OutputDeclarationView(it.format.name, it.schemaRef) },
    )

data class PromptMetadataView(
    val key: String,
    val name: String,
    val category: String?,
    val description: String?,
    val tags: List<String>,
)

fun PromptMetadata.toView(): PromptMetadataView = PromptMetadataView(key.value, name, category, description, tags)

data class GetPromptView(val metadata: PromptMetadataView?, val versions: List<PromptVersionView>)

fun GetPromptResult.toView(): GetPromptView = GetPromptView(metadata?.toView(), versions.map { it.toView() })

data class PromptSummaryView(
    val key: String,
    val name: String,
    val category: String?,
    val tags: List<String>,
    val status: String,
    val latestVersion: String,
    val publishedVersion: String?,
)

fun PromptSummary.toView(): PromptSummaryView =
    PromptSummaryView(
        key = key.value,
        name = name,
        category = category,
        tags = tags,
        status = status::class.simpleName ?: "Unknown",
        latestVersion = latestVersion,
        publishedVersion = publishedVersion,
    )

data class PromptVersionDiffView(
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

fun PromptVersionDiff.toView(): PromptVersionDiffView =
    PromptVersionDiffView(
        key = key.value,
        from = from.toString(),
        to = to.toString(),
        contentChanged = contentChanged,
        fromContentHash = fromContentHash,
        toContentHash = toContentHash,
        variablesChanged = variablesChanged,
        contextRequirementsChanged = contextRequirementsChanged,
        extendsChanged = extendsChanged,
        validationChanged = validationChanged,
        outputChanged = outputChanged,
    )
