package promptengine.domain.benchmark

/**
 * Benchmarkが算出する指標（設計書§2.12・§12 `benchmark_metrics.metric_type`、ADR-0035）。
 *
 * M2はこの3種に固定する（クローズドな集合。§16-6/§16-15の`EvaluationRule`/
 * `BenchmarkScoringRule`のような「差替可能な拡張点」とは別の軸であり、どの指標を
 * *要求するか*はここで閉じ、各指標の*採点方法*のみをPluginで差し替え可能にする）。
 */
enum class BenchmarkMetricType {
    Accuracy,
    Consistency,
    Determinism,
}
