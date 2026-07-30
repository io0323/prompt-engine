package promptengine.engine.optimization

import promptengine.domain.composition.CompiledPrompt
import promptengine.domain.context.ContextBindingSet
import promptengine.domain.optimization.ModelProfile
import promptengine.domain.optimization.OptimizationNote
import promptengine.domain.optimization.OptimizationRule
import promptengine.domain.optimization.RuleOptimizationResult
import promptengine.domain.optimization.TruncationNote
import promptengine.domain.shared.TokenCount
import promptengine.domain.tokenizer.TokenizerPlugin

/**
 * 会話履歴・Contextの切詰（設計書§2.11「Compression」）。適用条件は`tokenEstimate > budget`
 * （ADR-0013決定9）。要約（LLMによる意味圧縮）はAPAP連携が必要なためM1スコープ外とし、
 * 切詰のみを実装する（ADR-0013決定3、ユーザー確認済み）。
 *
 * 優先順位（§2.11）: `conversation`スコープの古い順（`List`の先頭＝最古と扱う） →
 * `memory`スコープ。各スコープ内で`List`型の値を持つキーをキー名でソートした順に処理し、
 * 各Listの先頭要素から間引く。切り詰めた実際のテキスト内容は[TruncationNote.summary]に
 * 含めない（件数・トークン数のみ）。
 */
class CompressionRule(
    private val tokenizerPlugin: TokenizerPlugin,
) : OptimizationRule {
    override fun id(): String = RULE_ID

    override fun applicable(
        compiled: CompiledPrompt,
        contextBindings: ContextBindingSet,
        profile: ModelProfile,
        estimatedTokens: TokenCount,
        budget: TokenCount,
    ): Boolean = estimatedTokens.value > budget.value

    override fun optimize(
        compiled: CompiledPrompt,
        contextBindings: ContextBindingSet,
        profile: ModelProfile,
        estimatedTokens: TokenCount,
        budget: TokenCount,
    ): RuleOptimizationResult {
        val deficit = estimatedTokens.value - budget.value
        if (deficit <= 0) {
            return RuleOptimizationResult(
                compiled,
                contextBindings,
                OptimizationNote(id(), TokenCount(0), "within budget"),
            )
        }

        val values = contextBindings.values.toMutableMap()
        val truncationNotes = mutableListOf<TruncationNote>()
        var remainingDeficit = deficit
        var totalShed = 0

        for (scope in PRIORITY_SCOPES) {
            for (key in candidateKeys(values, scope)) {
                if (remainingDeficit <= 0) break

                val outcome = truncateList(values.getValue(key) as List<*>, remainingDeficit)
                values[key] = outcome.remaining
                remainingDeficit -= outcome.tokensShed
                totalShed += outcome.tokensShed
                if (outcome.dropped > 0) truncationNotes += outcome.toTruncationNote(scope, key)
            }
        }

        val newContextBindings = ContextBindingSet(values, contextBindings.warnings)
        val detail = reportDetail(truncationNotes.size)
        return RuleOptimizationResult(
            compiled = compiled,
            contextBindings = newContextBindings,
            note = OptimizationNote(id(), TokenCount(totalShed), detail),
            truncations = truncationNotes,
        )
    }

    private fun candidateKeys(
        values: Map<String, Any>,
        scope: String,
    ): List<String> = values.keys.filter { it.startsWith("$scope.") && values[it] is List<*> }.sorted()

    private fun reportDetail(truncatedScopeCount: Int): String =
        if (truncatedScopeCount == 0) "no truncatable entries found" else "truncated $truncatedScopeCount scope(s)"

    private fun truncateList(
        list: List<*>,
        deficit: Int,
    ): TruncateOutcome {
        // deficit<=0はここに到達しない: 唯一の呼出元(optimize)がremainingDeficit<=0の時点でループをbreakする
        if (list.isEmpty()) return TruncateOutcome(list, dropped = 0, tokensShed = 0)

        val remaining = list.toMutableList()
        var tokensShed = 0
        var remainingDeficit = deficit
        var dropped = 0
        while (remainingDeficit > 0 && remaining.isNotEmpty()) {
            val removedTokens = tokenizerPlugin.estimate(canonicalString(remaining.removeAt(0))).value
            remainingDeficit -= removedTokens
            tokensShed += removedTokens
            dropped++
        }
        return TruncateOutcome(remaining, dropped, tokensShed)
    }

    private fun estimateTokens(list: List<*>): Int = list.sumOf { tokenizerPlugin.estimate(canonicalString(it)).value }

    private inner class TruncateOutcome(
        val remaining: List<*>,
        val dropped: Int,
        val tokensShed: Int,
    ) {
        fun toTruncationNote(
            scope: String,
            key: String,
        ): TruncationNote =
            TruncationNote(
                scope = scope,
                originalTokenEstimate = TokenCount(estimateTokens(remaining) + tokensShed),
                truncatedTokenEstimate = TokenCount(estimateTokens(remaining)),
                summary = "dropped $dropped oldest of ${dropped + remaining.size} entries from $key",
            )
    }

    private companion object {
        const val RULE_ID = "Compression"
        val PRIORITY_SCOPES = listOf("conversation", "memory")
    }
}
