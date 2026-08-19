package promptengine.infrastructure.observability

import io.micrometer.core.instrument.MeterRegistry
import promptengine.domain.execution.ExecutionErrorType
import promptengine.domain.observability.CacheOperation
import promptengine.domain.observability.MetricsRecorder
import promptengine.domain.observability.Outcome
import promptengine.domain.observability.TokenDirection
import promptengine.domain.pipeline.PipelineMode
import promptengine.domain.validation.Severity
import java.math.BigDecimal
import java.util.concurrent.TimeUnit

private const val TAG_STAGE = "stage"
private const val TAG_MODE = "mode"
private const val TAG_OUTCOME = "outcome"
private const val TAG_RULE_ID = "ruleId"
private const val TAG_SEVERITY = "severity"
private const val TAG_DIRECTION = "direction"
private const val TAG_ERROR_TYPE = "errorType"
private const val TAG_OPERATION = "operation"
private const val ERROR_TYPE_NONE = "NONE"

/**
 * [MetricsRecorder]のMicrometer実装（設計書§2.15、ADR-0027決定1）。
 *
 * ラベルは[promptengine.domain.observability.MetricsRecorder]のKDoc通り、`stage`（Pipeline構成が
 * 固定する12種）・`mode`（3種）・`outcome`（2種）・`ruleId`（導入Pluginの数だけの固定集合）・
 * `severity`（3種）・`direction`（2種）・`errorType`（[ExecutionErrorType]の8種+`NONE`）・
 * `operation`（[CacheOperation]の3種）のみを使う。`promptKey`/`version`/`traceId`をタグに使う
 * メソッドは[MetricsRecorder]自体に存在しない
 * ため、この実装がそれらを追加で付与することもない（[MicrometerMetricsRecorderCardinalityTest]
 * が実際に記録された全メトリクスのタグキーを許可リストと突き合わせて回帰を検知する）。
 */
class MicrometerMetricsRecorder(
    private val registry: MeterRegistry,
) : MetricsRecorder {
    override fun recordStageDuration(
        stage: String,
        mode: PipelineMode,
        durationMs: Long,
    ) {
        registry.timer(STAGE_DURATION_METRIC, TAG_STAGE, stage, TAG_MODE, mode.name)
            .record(durationMs, TimeUnit.MILLISECONDS)
    }

    override fun recordRenderDuration(
        mode: PipelineMode,
        outcome: Outcome,
        durationMs: Long,
    ) {
        registry.timer(RENDER_DURATION_METRIC, TAG_MODE, mode.name, TAG_OUTCOME, outcome.name)
            .record(durationMs, TimeUnit.MILLISECONDS)
    }

    override fun incrementRenderCount(outcome: Outcome) {
        registry.counter(RENDER_COUNT_METRIC, TAG_OUTCOME, outcome.name).increment()
    }

    override fun incrementValidationIssue(
        ruleId: String,
        severity: Severity,
    ) {
        registry.counter(VALIDATION_FAILURE_COUNT_METRIC, TAG_RULE_ID, ruleId, TAG_SEVERITY, severity.name)
            .increment()
    }

    override fun recordTokenUsage(
        direction: TokenDirection,
        amount: Long,
    ) {
        registry.counter(TOKEN_USAGE_TOTAL_METRIC, TAG_DIRECTION, direction.name).increment(amount.toDouble())
    }

    override fun recordCost(amount: BigDecimal) {
        registry.counter(COST_TOTAL_METRIC).increment(amount.toDouble())
    }

    override fun incrementExecutionAttempt(
        outcome: Outcome,
        errorType: ExecutionErrorType?,
    ) {
        registry.counter(
            EXECUTION_ATTEMPTS_TOTAL_METRIC,
            TAG_OUTCOME,
            outcome.name,
            TAG_ERROR_TYPE,
            errorType?.name ?: ERROR_TYPE_NONE,
        ).increment()
    }

    override fun incrementCacheDegradation(operation: CacheOperation) {
        registry.counter(CACHE_DEGRADATION_TOTAL_METRIC, TAG_OPERATION, operation.name).increment()
    }

    private companion object {
        const val STAGE_DURATION_METRIC = "pipeline_stage_duration_seconds"
        const val RENDER_DURATION_METRIC = "pipeline_render_duration_seconds"
        const val RENDER_COUNT_METRIC = "render_count_total"
        const val VALIDATION_FAILURE_COUNT_METRIC = "validation_failure_count_total"
        const val TOKEN_USAGE_TOTAL_METRIC = "token_usage_total"
        const val COST_TOTAL_METRIC = "cost_total"
        const val EXECUTION_ATTEMPTS_TOTAL_METRIC = "execution_attempts_total"
        const val CACHE_DEGRADATION_TOTAL_METRIC = "cache_degradation_total"
    }
}
