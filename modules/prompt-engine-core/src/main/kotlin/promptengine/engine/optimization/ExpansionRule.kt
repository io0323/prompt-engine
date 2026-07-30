package promptengine.engine.optimization

import promptengine.domain.composition.CompiledPrompt
import promptengine.domain.context.ContextBindingSet
import promptengine.domain.optimization.ModelCapability
import promptengine.domain.optimization.ModelProfile
import promptengine.domain.optimization.OptimizationNote
import promptengine.domain.optimization.OptimizationRule
import promptengine.domain.optimization.RuleOptimizationResult
import promptengine.domain.shared.TokenCount
import promptengine.domain.template.ast.BlockNode
import promptengine.domain.template.ast.BlockRole
import promptengine.domain.template.ast.TextNode

/**
 * ModelProfileが指示追従弱と定義する場合の詳細指示の追加（設計書§2.11「Expansion」）。
 *
 * §2.11は「Few-shot例・詳細指示の追加」と定義するが、Few-shot例の追加には具体例データの
 * 供給元（Fragment参照・DSL宣言等）が必要であり、本ADR（ADR-0013）・現行ドメインモデルには
 * その供給元が定義されていない。M1では「詳細指示の追加」のみを実装し、Few-shot例の追加は
 * 供給元が定義されるまで対象外とする。
 *
 * 最初に見つかった`SYSTEM`roleの[BlockNode]の末尾へ、既存本文と連結して読めるよう
 * 空行区切り（`"\n\n"`）を挟んで[supplementalInstruction]を追記する（区切りが無いと
 * 既存テキストと単語が直接くっつき、例えば`"base instruction"`+`"be precise"`が
 * `"base instructionbe precise"`のように読めなくなる）。`SYSTEM`ブロックが1つも無ければ、
 * 本文先頭に新規`SYSTEM`ブロックとして追加する（この場合は区切り不要）。
 */
class ExpansionRule(
    private val supplementalInstruction: String = DEFAULT_INSTRUCTION,
) : OptimizationRule {
    override fun id(): String = RULE_ID

    override fun applicable(
        compiled: CompiledPrompt,
        contextBindings: ContextBindingSet,
        profile: ModelProfile,
        estimatedTokens: TokenCount,
        budget: TokenCount,
    ): Boolean = profile.capabilities.contains(ModelCapability.WEAK_INSTRUCTION_FOLLOWING)

    override fun optimize(
        compiled: CompiledPrompt,
        contextBindings: ContextBindingSet,
        profile: ModelProfile,
        estimatedTokens: TokenCount,
        budget: TokenCount,
    ): RuleOptimizationResult {
        val body = compiled.body
        val systemIndex = body.indexOfFirst { it is BlockNode && it.role == BlockRole.SYSTEM }
        val newBody =
            if (systemIndex >= 0) {
                val block = body[systemIndex] as BlockNode
                body.toMutableList().apply {
                    this[systemIndex] = block.copy(body = block.body + TextNode("\n\n$supplementalInstruction"))
                }
            } else {
                listOf(BlockNode(BlockRole.SYSTEM, listOf(TextNode(supplementalInstruction)))) + body
            }

        return RuleOptimizationResult(
            compiled = compiled.copy(body = newBody),
            contextBindings = contextBindings,
            note =
                OptimizationNote(
                    id(),
                    TokenCount(0),
                    "added supplemental instruction for weak-instruction-following model",
                ),
        )
    }

    private companion object {
        const val RULE_ID = "Expansion"
        const val DEFAULT_INSTRUCTION =
            "Follow the instructions precisely and complete every step without omission."
    }
}
