package promptengine.infrastructure.observability

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.junit.jupiter.api.Test

/** [OpenTelemetryPipelineTracer]の単体テスト（設計書§2.15、Issue #38、ADR-0027決定2）。 */
class OpenTelemetryPipelineTracerTest {
    private fun newTracerWithExporter(): Pair<OpenTelemetryPipelineTracer, InMemorySpanExporter> {
        val exporter = InMemorySpanExporter.create()
        val tracerProvider =
            SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()
        val tracer = tracerProvider.get("test")
        return OpenTelemetryPipelineTracer(tracer) to exporter
    }

    @Test
    fun `withSpanはblockを実行しその戻り値をそのまま返す`() {
        val (tracer, _) = newTracerWithExporter()

        val result = tracer.withSpan("Load", "trace-1") { 42 }

        result shouldBe 42
    }

    @Test
    fun `withSpanはstageName属性pe_trace_idを持つSpanを記録する`() {
        val (tracer, exporter) = newTracerWithExporter()

        tracer.withSpan("Load", "trace-1") { "ok" }

        val span = exporter.finishedSpanItems.single()
        span.name shouldBe "Load"
        span.attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey("pe.trace_id")) shouldBe "trace-1"
        span.status.statusCode shouldBe StatusCode.UNSET
    }

    @Test
    fun `同一traceIdの複数Spanは同一OTel TraceIdを共有する`() {
        val (tracer, exporter) = newTracerWithExporter()

        tracer.withSpan("Load", "trace-shared") { }
        tracer.withSpan("Merge", "trace-shared") { }
        tracer.withSpan("Import", "trace-different") { }

        val spans = exporter.finishedSpanItems.associateBy { it.name }
        spans.getValue("Load").traceId shouldBe spans.getValue("Merge").traceId
        spans.getValue("Load").traceId shouldNotBe spans.getValue("Import").traceId
    }

    @Test
    fun `blockが例外を投げた場合Spanはエラー状態を記録しつつ例外はそのまま伝播する`() {
        val (tracer, exporter) = newTracerWithExporter()
        val failure = IllegalStateException("boom")

        shouldThrow<IllegalStateException> {
            tracer.withSpan("Execution", "trace-error") { throw failure }
        }

        val span = exporter.finishedSpanItems.single()
        span.status.statusCode shouldBe StatusCode.ERROR
        span.events.any { it.name == "exception" } shouldBe true
    }
}
