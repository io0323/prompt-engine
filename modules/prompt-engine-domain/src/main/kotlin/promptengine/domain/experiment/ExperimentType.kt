package promptengine.domain.experiment

/**
 * Experimentの種別（設計書§12 `experiments.type`、ADR-0034）。
 *
 * `AB`/`CANARY`はAggregate・永続化モデルを共有し、運用上の違い（重みを段階的に
 * 変えるか否か）のみで区別する（ADR-0034決定6）。`BENCHMARK`（オフライン評価）は
 * 別ADR・別PRのスコープ（`docs/prompts/m2-4a.md`）。
 */
enum class ExperimentType {
    AB,
    CANARY,
}
