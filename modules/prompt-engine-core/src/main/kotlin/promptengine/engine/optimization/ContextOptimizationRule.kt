package promptengine.engine.optimization

import promptengine.domain.composition.CompiledPrompt
import promptengine.domain.context.ContextBindingSet
import promptengine.domain.optimization.ModelProfile
import promptengine.domain.optimization.OptimizationNote
import promptengine.domain.optimization.OptimizationRule
import promptengine.domain.optimization.RuleOptimizationResult
import promptengine.domain.shared.TokenCount
import promptengine.domain.tokenizer.TokenizerPlugin
import promptengine.engine.validation.PropertyRefCollector

/**
 * 参照されないContextスコープの除去（設計書§2.11「Context Optimization」）。常時適用。
 *
 * [PropertyRefCollector]（`engine.validation`、同一モジュール内）で[CompiledPrompt.body]から
 * 実際に参照される`context.<scope>.<path>`形式の`PropertyRef`を収集し、そのスコープ
 * （`path[1]`）集合に含まれない[ContextBindingSet.values]のエントリ（キーの先頭セグメント
 * がスコープ名、`"<scope>.<path>"`）を除去する。
 */
class ContextOptimizationRule(
    private val tokenizerPlugin: TokenizerPlugin,
) : OptimizationRule {
    override fun id(): String = RULE_ID

    override fun applicable(
        compiled: CompiledPrompt,
        contextBindings: ContextBindingSet,
        profile: ModelProfile,
        estimatedTokens: TokenCount,
        budget: TokenCount,
    ): Boolean = true

    override fun optimize(
        compiled: CompiledPrompt,
        contextBindings: ContextBindingSet,
        profile: ModelProfile,
        estimatedTokens: TokenCount,
        budget: TokenCount,
    ): RuleOptimizationResult {
        val referencedScopes =
            PropertyRefCollector.collect(compiled.body)
                .filter { it.path.first() == CONTEXT_SEGMENT && it.path.size >= MIN_CONTEXT_PATH_SIZE }
                .map { it.path[1] }
                .toSet()

        val (kept, removed) =
            contextBindings.values.entries.partition {
                referencedScopes.contains(it.key.substringBefore("."))
            }

        if (removed.isEmpty()) {
            return RuleOptimizationResult(
                compiled = compiled,
                contextBindings = contextBindings,
                note = OptimizationNote(id(), TokenCount(0), "no unreferenced context scopes found"),
            )
        }

        val tokensSaved = removed.sumOf { tokenizerPlugin.estimate(canonicalString(it.value)).value }
        val removedScopes = removed.map { it.key.substringBefore(".") }.toSortedSet()
        val newContextBindings = ContextBindingSet(kept.associate { it.key to it.value }, contextBindings.warnings)

        return RuleOptimizationResult(
            compiled = compiled,
            contextBindings = newContextBindings,
            note =
                OptimizationNote(
                    id(),
                    TokenCount(tokensSaved),
                    "removed unreferenced context scopes: ${removedScopes.joinToString(", ")}",
                ),
        )
    }

    private companion object {
        const val RULE_ID = "ContextOptimization"
        const val CONTEXT_SEGMENT = "context"
        const val MIN_CONTEXT_PATH_SIZE = 3
    }
}
