package promptengine.domain.pipeline

/**
 * ステージ単位のTrace Span生成の抽象（設計書§2.6冒頭「ステージ単位でTrace Span生成・
 * 所要時間記録」、実装ガイド§6.9「OpenTelemetry Spanを作り所要時間を記録」）。
 *
 * `prompt-engine-application`はOpenTelemetryへ直接依存できない（P4で追加したArchUnitの
 * フレームワーク隔離規約、`AuditFailureHandler`と同じ理由）ため、`PipelineOrchestrator`は
 * この抽象のみを参照する。実装（`OpenTelemetryPipelineTracer`、OpenTelemetry API使用）は
 * `prompt-engine-infrastructure`に置く。所要時間の測定自体（`stageDurationsMs`）は
 * フレームワーク非依存（`System.nanoTime()`）のため`PipelineOrchestrator`が自前で行い、
 * この抽象はSpan生成（分散トレーシング）のみを担う。実OpenTelemetry実装は
 * [Issue #38](https://github.com/io0323/prompt-engine/issues/38)で追跡する。
 */
interface PipelineTracer {
    /** [stageName]のSpanを開始し、[block]を実行してその結果を返す。[traceId]をSpanへ伝播する。 */
    fun <T> withSpan(
        stageName: String,
        traceId: String,
        block: () -> T,
    ): T
}
