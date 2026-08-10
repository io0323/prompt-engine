package promptengine.infrastructure.observability

import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import promptengine.domain.execution.ExecutionErrorType
import promptengine.domain.observability.Outcome
import promptengine.domain.observability.TokenDirection
import promptengine.domain.pipeline.PipelineMode
import promptengine.domain.validation.Severity
import java.math.BigDecimal
import java.util.concurrent.TimeUnit

/** [MicrometerMetricsRecorder]の単体テスト（設計書§2.15、ADR-0027決定1）。 */
class MicrometerMetricsRecorderTest {
    @Test
    fun `recordStageDurationはstageとmodeタグ付きでpipeline_stage_duration_secondsへ記録する`() {
        val registry = SimpleMeterRegistry()
        val recorder = MicrometerMetricsRecorder(registry)

        recorder.recordStageDuration("Load", PipelineMode.FULL_EXECUTION, 42)

        val timer =
            registry.get("pipeline_stage_duration_seconds")
                .tag("stage", "Load")
                .tag("mode", "FULL_EXECUTION")
                .timer()
        timer.count() shouldBe 1L
        timer.totalTime(TimeUnit.MILLISECONDS) shouldBe 42.0
    }

    @Test
    fun `recordRenderDurationはmodeとoutcomeタグ付きでpipeline_render_duration_secondsへ記録する`() {
        val registry = SimpleMeterRegistry()
        val recorder = MicrometerMetricsRecorder(registry)

        recorder.recordRenderDuration(PipelineMode.RENDER_ONLY, Outcome.SUCCESS, 100)

        val timer =
            registry.get("pipeline_render_duration_seconds")
                .tag("mode", "RENDER_ONLY")
                .tag("outcome", "SUCCESS")
                .timer()
        timer.count() shouldBe 1L
    }

    @Test
    fun `incrementRenderCountはoutcomeタグ付きでrender_count_totalを加算する`() {
        val registry = SimpleMeterRegistry()
        val recorder = MicrometerMetricsRecorder(registry)

        recorder.incrementRenderCount(Outcome.SUCCESS)
        recorder.incrementRenderCount(Outcome.SUCCESS)
        recorder.incrementRenderCount(Outcome.FAILURE)

        registry.get("render_count_total").tag("outcome", "SUCCESS").counter().count() shouldBe 2.0
        registry.get("render_count_total").tag("outcome", "FAILURE").counter().count() shouldBe 1.0
    }

    @Test
    fun `incrementValidationIssueはruleIdとseverityタグ付きでvalidation_failure_count_totalを加算する`() {
        val registry = SimpleMeterRegistry()
        val recorder = MicrometerMetricsRecorder(registry)

        recorder.incrementValidationIssue("policy.forbidden-word", Severity.ERROR)

        registry.get("validation_failure_count_total")
            .tag("ruleId", "policy.forbidden-word")
            .tag("severity", "ERROR")
            .counter()
            .count() shouldBe 1.0
    }

    @Test
    fun `recordTokenUsageはdirectionタグ付きでtoken_usage_totalへamountを加算する`() {
        val registry = SimpleMeterRegistry()
        val recorder = MicrometerMetricsRecorder(registry)

        recorder.recordTokenUsage(TokenDirection.INPUT, 120)
        recorder.recordTokenUsage(TokenDirection.OUTPUT, 30)

        registry.get("token_usage_total").tag("direction", "INPUT").counter().count() shouldBe 120.0
        registry.get("token_usage_total").tag("direction", "OUTPUT").counter().count() shouldBe 30.0
    }

    @Test
    fun `recordCostはタグ無しでcost_totalへamountを加算する`() {
        val registry = SimpleMeterRegistry()
        val recorder = MicrometerMetricsRecorder(registry)

        recorder.recordCost(BigDecimal("0.05"))
        recorder.recordCost(BigDecimal("0.02"))

        registry.get("cost_total").counter().count() shouldBe 0.07
    }

    @Test
    fun `incrementExecutionAttemptは成功時errorTypeをNONEとして記録する`() {
        val registry = SimpleMeterRegistry()
        val recorder = MicrometerMetricsRecorder(registry)

        recorder.incrementExecutionAttempt(Outcome.SUCCESS, errorType = null)

        registry.get("execution_attempts_total")
            .tag("outcome", "SUCCESS")
            .tag("errorType", "NONE")
            .counter()
            .count() shouldBe 1.0
    }

    @Test
    fun `incrementExecutionAttemptは失敗時ExecutionErrorTypeをそのままタグへ載せる`() {
        val registry = SimpleMeterRegistry()
        val recorder = MicrometerMetricsRecorder(registry)

        recorder.incrementExecutionAttempt(Outcome.FAILURE, ExecutionErrorType.READ_TIMEOUT)

        registry.get("execution_attempts_total")
            .tag("outcome", "FAILURE")
            .tag("errorType", "READ_TIMEOUT")
            .counter()
            .count() shouldBe 1.0
    }
}
