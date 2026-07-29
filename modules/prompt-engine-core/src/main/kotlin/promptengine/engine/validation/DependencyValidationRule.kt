package promptengine.engine.validation

import promptengine.domain.composition.CompiledPrompt
import promptengine.domain.composition.ResolvedDependency
import promptengine.domain.context.ContextBindingSet
import promptengine.domain.shared.PublicationState
import promptengine.domain.validation.Finding
import promptengine.domain.validation.Severity
import promptengine.domain.validation.ValidationRule
import promptengine.domain.variable.BindingSet

/**
 * 参照Fragment/TemplateのStatus検証（設計書§2.10）。[CompiledPrompt.dependencies]が
 * 既に確定済みのStatusをそのまま報告するのみで、リポジトリを再度引かない
 * （ADR-0012決定7。StatusゲートそのものはP3c CompositionServiceが解決時点で行う
 * ── STANDARDモードでは`DraftReferenceNotAllowedException`により非Published参照は
 * 既に拒否済みのため、ここに到達する時点で全依存は必ずPublished。このRuleが
 * 実際に何かを報告するのはCOMPILE_ONLYモード（Draft参照が意図的に許可される）のみ）。
 * したがって固定でWARNING（ブロッカーではなく注意喚起）とする。
 */
class DependencyValidationRule : ValidationRule {
    override fun id(): String = RULE_ID

    override fun severity(): Severity = Severity.WARNING

    override fun validate(
        compiled: CompiledPrompt,
        variableBindings: BindingSet,
        contextBindings: ContextBindingSet,
    ): List<Finding> =
        compiled.dependencies
            .filter { it.status != PublicationState.Published }
            .map { dependency ->
                val (path, description) =
                    when (dependency) {
                        is ResolvedDependency.TemplateDependency ->
                            "$.dependencies.templates.${dependency.key.value}" to dependency.key.value
                        is ResolvedDependency.FragmentDependency ->
                            "$.dependencies.fragments.${dependency.key.value}" to dependency.key.value
                    }
                Finding(
                    ruleId = id(),
                    path = path,
                    severity = severity(),
                    message =
                        "dependency '$description'@${dependency.resolvedVersion} is not Published " +
                            "(status=${dependency.status})",
                )
            }

    companion object {
        const val RULE_ID = "DependencyValidation"
    }
}
