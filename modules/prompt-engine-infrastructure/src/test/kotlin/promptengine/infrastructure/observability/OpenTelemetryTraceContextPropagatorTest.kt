package promptengine.infrastructure.observability

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.junit.jupiter.api.Test

/**
 * [OpenTelemetryTraceContextPropagator]の単体テスト（設計書§2.15、Issue #38、ADR-0027決定2）。
 *
 * 実APAP接続（M2）はまだ存在しないため、ここでは「[OpenTelemetryPipelineTracer]が生成する
 * Spanと同じOTel Traceに相関する妥当なW3C `traceparent`文字列を返す」ことのみを検証する。
 */
class OpenTelemetryTraceContextPropagatorTest {
    private val propagator = OpenTelemetryTraceContextPropagator()

    @Test
    fun `traceparentForはW3C仕様の形式00-traceId-spanId-flagsで返す`() {
        val header = propagator.traceparentFor("trace-1")

        header shouldMatch Regex("^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$")
    }

    @Test
    fun `同一traceIdは常に同じtraceparentを返す`() {
        propagator.traceparentFor("trace-stable") shouldBe propagator.traceparentFor("trace-stable")
    }

    @Test
    fun `異なるtraceIdは異なるtraceparentを返す`() {
        propagator.traceparentFor("trace-a") shouldNotBe propagator.traceparentFor("trace-b")
    }

    @Test
    fun `traceparentのtraceId部分はPipelineTracerが生成するSpanと同一Traceに相関する`() {
        val exporter = InMemorySpanExporter.create()
        val tracerProvider =
            SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()
        val pipelineTracer = OpenTelemetryPipelineTracer(tracerProvider.get("test"))
        val traceId = "trace-correlated"

        pipelineTracer.withSpan("Load", traceId) { }
        val header = propagator.traceparentFor(traceId)
        val span = exporter.finishedSpanItems.single()

        header.split("-")[1] shouldBe span.traceId
    }
}
