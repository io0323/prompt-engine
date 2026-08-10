package promptengine.domain.observability

import promptengine.domain.execution.ExecutionErrorType
import promptengine.domain.pipeline.PipelineMode
import promptengine.domain.validation.Severity
import java.math.BigDecimal

/**
 * Micrometerメトリクス記録の抽象（設計書§2.15、ADR-0027）。
 *
 * `prompt-engine-application`はMicrometerへ直接依存できない（[promptengine.domain.pipeline.PipelineTracer]
 * と同じ理由、CLAUDE.mdのフレームワーク隔離規約）ため、`PipelineOrchestrator`/`ValidationStage`は
 * この抽象のみを参照する。実装（`MicrometerMetricsRecorder`）は`prompt-engine-infrastructure`に置く。
 *
 * 各メソッドの引数は意図的に`String`ではなく有界な型（[PipelineMode]・[Outcome]・
 * [TokenDirection]・[Severity]・[ExecutionErrorType]）またはドキュメントで有界と明記した
 * `String`（`stage`/`ruleId`はPipeline構成・導入Pluginの数だけの固定集合であり、
 * PromptやVersionの数には比例しない）に限定している。`promptKey`/`version`/`traceId`を
 * ラベルとして受け取るメソッドは存在しない（ADR-0027決定1、Prompt単位の分析は
 * `execution_logs`のクエリで行う）。
 */
interface MetricsRecorder {
    /** ステージ`stage`（`PipelineStage.name`）の所要時間[durationMs]を記録する（成功・失敗を問わず毎回）。 */
    fun recordStageDuration(
        stage: String,
        mode: PipelineMode,
        durationMs: Long,
    )

    /** Render(Stage 1〜8)全体の所要時間[durationMs]を記録する（NFR-003 SLO監視用）。 */
    fun recordRenderDuration(
        mode: PipelineMode,
        outcome: Outcome,
        durationMs: Long,
    )

    /** Rendering(Stage 8)が完了した回数を[outcome]別に加算する。 */
    fun incrementRenderCount(outcome: Outcome)

    /** Validation(Stage 6)が検出した[Finding][promptengine.domain.validation.Finding]1件ごとに加算する。 */
    fun incrementValidationIssue(
        ruleId: String,
        severity: Severity,
    )

    /** Execution(Stage 9)で消費したトークン数[amount]を[direction]別に加算する。 */
    fun recordTokenUsage(
        direction: TokenDirection,
        amount: Long,
    )

    /** Execution(Stage 9)で発生したコスト[amount]を加算する。 */
    fun recordCost(amount: BigDecimal)

    /**
     * Execution(Stage 9)の試行を[outcome]別に加算する。`execution_success_rate`
     * （設計書§2.15）はこのカウンタの比率として監視基盤側（PromQL等）で算出し、
     * アプリ内では比率を保持しない（ADR-0027決定1）。[errorType]は[outcome]が
     * [Outcome.FAILURE]かつ[promptengine.domain.execution.ExecutionFailedException]由来の
     * 場合のみ非null。
     */
    fun incrementExecutionAttempt(
        outcome: Outcome,
        errorType: ExecutionErrorType?,
    )
}

/** [MetricsRecorder]の各カウンタ/Timerに付与する結果ラベル。 */
enum class Outcome { SUCCESS, FAILURE }

/** [MetricsRecorder.recordTokenUsage]のトークン方向ラベル。 */
enum class TokenDirection { INPUT, OUTPUT }
