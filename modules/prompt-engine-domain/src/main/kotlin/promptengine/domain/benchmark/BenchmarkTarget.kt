package promptengine.domain.benchmark

import promptengine.domain.shared.SemVer
import java.util.UUID

/**
 * Benchmarkが評価するPromptVersionの1件（設計書§12 `benchmark_targets`、ADR-0035決定6）。
 *
 * `Variant`（`weightPct`を持つ、トラフィック配分の単位）とは意図的に別の型である。
 * Benchmarkにトラフィック配分という概念自体が存在しないため、同じ型を使い回すと
 * 「重みを持たないVariant」という混乱を生む。
 */
data class BenchmarkTarget(
    val targetId: UUID,
    val promptVersionSemVer: SemVer,
)
