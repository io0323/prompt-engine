package promptengine.domain.execution

import promptengine.domain.parsing.OutputSchema
import promptengine.domain.render.RenderedPrompt
import promptengine.domain.shared.TokenCount

/**
 * Execution(ステージ9)+Response Parsing(ステージ10)の統括Interface（ADR-0015決定1）。
 *
 * `ExecutionCoordinator`（`prompt-engine-core`、ADR-0014決定6）はP7時点では
 * 「§16拡張ポイントに定義が無い」ことを理由にdomain Interfaceを持たない具象クラスとして
 * 新設されたが、Pipeline Orchestrator（P8、`prompt-engine-application`）が
 * `prompt-engine-core`に依存できない（CLAUDE.mdの絶対規約）ため、Stage 9・10の実装が
 * `ExecutionCoordinator`を直接参照できない。本Interfaceは、上位レイヤ（Pipeline）の
 * 依存性逆転のみを理由に新設する（ADR-0015が確立した原則: domainにInterfaceを置く理由は
 * 拡張ポイントであることに限らない）。
 */
interface ExecutionEngine {
    /**
     * 実装契約は`ExecutionCoordinator`（`prompt-engine-core`、ADR-0014）のKDocを参照。
     * 失敗時は[ExecutionFailedException]・[promptengine.domain.parsing.ParseFailedException]・
     * [promptengine.domain.optimization.TokenBudgetExceededException]のいずれかを投げる。
     */
    fun run(
        rendered: RenderedPrompt,
        policy: ExecutionPolicy,
        schema: OutputSchema?,
        budget: TokenCount,
    ): ExecutionOutcome
}
