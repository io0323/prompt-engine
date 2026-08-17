package promptengine.domain.benchmark

import java.util.UUID

/**
 * [GoldenDataset]の1件（設計書§12 `golden_dataset_items`、ADR-0035）。
 *
 * [parameters]/[context]は`ExecuteRequestDto`と同じ形（`Map<String, Any?>`/
 * `Map<String, Map<String, Any?>>`）を取る。パイプラインへ実行を投入するために必要な
 * 入力一式（Version/実行方針を除く）そのものであるため。
 *
 * [expectedOutput]はAccuracy算出時のみ実質必須（`BenchmarkScoringRule`が使う）。
 * Consistency/Determinismは同一入力のN回実行同士を比較するだけで成立し、期待出力を
 * 必要としないため、DB制約ではなくアプリケーション層でAccuracy算出時にのみ検証する
 * （ADR-0035決定2）。
 */
data class GoldenDatasetItem(
    val itemId: UUID,
    val parameters: Map<String, Any?>,
    val context: Map<String, Map<String, Any?>>,
    val expectedOutput: String?,
    val metadata: Map<String, String> = emptyMap(),
)
