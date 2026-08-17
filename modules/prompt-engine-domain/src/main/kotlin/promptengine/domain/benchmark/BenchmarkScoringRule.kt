package promptengine.domain.benchmark

import java.math.BigDecimal

/**
 * Benchmark採点の拡張点（設計書§16-15、ADR-0035決定4）。
 *
 * 既存の`EvaluationRule`（§16-6）は1回の実行のメタデータ（usage/latency等）のみを
 * 受け取る形であり、期待出力との比較（Accuracy）や複数回実行同士の比較
 * （Consistency/Determinism）を表現できない。そのため`EvaluationRule`を拡張せず、
 * 独立した拡張点として新設する。
 *
 * Plugin実装のパッケージは既存規約（ADR-0003）に倣い`promptengine.plugin.benchmark.<name>`
 * とする。
 */
interface BenchmarkScoringRule {
    /** 設計書§12 `benchmark_item_results`の対応カラムに書き込む指標名（例: `"Accuracy"`）。 */
    val metricType: String

    /**
     * [actualOutputs]（同一入力のN回実行結果）と[expectedOutput]からスコアを算出する。
     * [expectedOutput]が無い、または算出不能な場合は`null`を返す（スコア0とは区別する。
     * `EvaluationRule.evaluate`と同じ規約）。
     */
    fun score(
        actualOutputs: List<String>,
        expectedOutput: String?,
    ): BigDecimal?
}
