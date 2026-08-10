package promptengine.application.pipeline

import promptengine.domain.execution.ExecutionErrorType
import promptengine.domain.observability.MetricsRecorder
import promptengine.domain.observability.Outcome
import promptengine.domain.observability.TokenDirection
import promptengine.domain.pipeline.PipelineMode
import promptengine.domain.validation.Severity
import java.math.BigDecimal

/**
 * [MetricsRecorder]のテスト用記録実装。`PipelineOrchestratorTest`/`ValidationStageTest`/
 * `PipelineStageGuardsTest`で共用するため、単一ファイルの公開クラスとして定義する。
 */
class RecordingMetricsRecorder : MetricsRecorder {
    data class StageDurationCall(val stage: String, val mode: PipelineMode, val durationMs: Long)

    data class RenderDurationCall(val mode: PipelineMode, val outcome: Outcome, val durationMs: Long)

    data class ValidationIssueCall(val ruleId: String, val severity: Severity)

    data class TokenUsageCall(val direction: TokenDirection, val amount: Long)

    data class ExecutionAttemptCall(val outcome: Outcome, val errorType: ExecutionErrorType?)

    val stageDurations = mutableListOf<StageDurationCall>()
    val renderDurations = mutableListOf<RenderDurationCall>()
    val renderCounts = mutableListOf<Outcome>()
    val validationIssues = mutableListOf<ValidationIssueCall>()
    val tokenUsages = mutableListOf<TokenUsageCall>()
    val costs = mutableListOf<BigDecimal>()
    val executionAttempts = mutableListOf<ExecutionAttemptCall>()

    override fun recordStageDuration(
        stage: String,
        mode: PipelineMode,
        durationMs: Long,
    ) {
        stageDurations += StageDurationCall(stage, mode, durationMs)
    }

    override fun recordRenderDuration(
        mode: PipelineMode,
        outcome: Outcome,
        durationMs: Long,
    ) {
        renderDurations += RenderDurationCall(mode, outcome, durationMs)
    }

    override fun incrementRenderCount(outcome: Outcome) {
        renderCounts += outcome
    }

    override fun incrementValidationIssue(
        ruleId: String,
        severity: Severity,
    ) {
        validationIssues += ValidationIssueCall(ruleId, severity)
    }

    override fun recordTokenUsage(
        direction: TokenDirection,
        amount: Long,
    ) {
        tokenUsages += TokenUsageCall(direction, amount)
    }

    override fun recordCost(amount: BigDecimal) {
        costs += amount
    }

    override fun incrementExecutionAttempt(
        outcome: Outcome,
        errorType: ExecutionErrorType?,
    ) {
        executionAttempts += ExecutionAttemptCall(outcome, errorType)
    }
}
