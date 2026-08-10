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

/**
 * §2.15のメトリクスに高カーディナリティなラベル（`promptKey`/`version`/`traceId`）が
 * 付与されないことを保証する回帰テスト（設計書§2.15、ADR-0027決定1）。
 *
 * [MetricsRecorder][promptengine.domain.observability.MetricsRecorder]の全メソッドを一通り
 * 呼び出した上で、実際に[SimpleMeterRegistry]へ記録された全メーターの全タグキーを
 * 許可リストと突き合わせる。将来誰かが[MicrometerMetricsRecorder]の実装へ
 * `.tag("promptKey", key)`等を追加すると、このテストのタグキー集合チェックで検出される
 * （メトリクスの値そのものではなく構造を守るテストであるため、`MicrometerMetricsRecorderTest`
 * とは別ファイルに分離する）。
 */
class MicrometerMetricsRecorderCardinalityTest {
    @Test
    fun `全メトリクスのタグキーは許可リストのみで構成される`() {
        val registry = SimpleMeterRegistry()
        val recorder = MicrometerMetricsRecorder(registry)

        recorder.recordStageDuration("Load", PipelineMode.FULL_EXECUTION, 1)
        recorder.recordRenderDuration(PipelineMode.RENDER_ONLY, Outcome.SUCCESS, 1)
        recorder.incrementRenderCount(Outcome.SUCCESS)
        recorder.incrementValidationIssue("policy.forbidden-word", Severity.WARNING)
        recorder.recordTokenUsage(TokenDirection.INPUT, 1)
        recorder.recordCost(BigDecimal.ONE)
        recorder.incrementExecutionAttempt(Outcome.FAILURE, ExecutionErrorType.RATE_LIMITED)

        val actualTagKeys = registry.meters.flatMap { meter -> meter.id.tags.map { it.key } }.toSet()

        actualTagKeys shouldBe ALLOWED_TAG_KEYS
    }

    @Test
    fun `高カーディナリティラベルは許可リストに含まれない`() {
        HIGH_CARDINALITY_TAG_KEYS.forEach { forbidden -> (forbidden in ALLOWED_TAG_KEYS) shouldBe false }
    }

    private companion object {
        val ALLOWED_TAG_KEYS =
            setOf("stage", "mode", "outcome", "ruleId", "severity", "direction", "errorType")
        val HIGH_CARDINALITY_TAG_KEYS = setOf("promptKey", "version", "traceId")
    }
}
