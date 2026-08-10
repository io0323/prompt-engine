package promptengine.infrastructure.observability

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapSetter
import promptengine.domain.observability.TraceContextPropagator

private const val TRACEPARENT_HEADER = "traceparent"

/**
 * [TraceContextPropagator]のOpenTelemetry実装（設計書§2.15、Issue #38、ADR-0027決定2）。
 *
 * [OpenTelemetryPipelineTracer]と同じ[deterministicSpanContext]を使うため、同一traceIdに
 * 対して生成した`traceparent`ヘッダは、その`run()`内の全Stage Spanと同一OTel Traceに相関する。
 * ヘッダの実際の文字列組立ては自前実装せず、OTelの標準[W3CTraceContextPropagator]の
 * `inject`を使う（W3C仕様の細部・将来の改訂に対して自前フォーマットより堅牢）。
 */
class OpenTelemetryTraceContextPropagator : TraceContextPropagator {
    override fun traceparentFor(traceId: String): String {
        val context = Context.root().with(Span.wrap(deterministicSpanContext(traceId)))
        val carrier = mutableMapOf<String, String>()
        W3CTraceContextPropagator.getInstance().inject(context, carrier, MAP_SETTER)
        return carrier.getValue(TRACEPARENT_HEADER)
    }

    private companion object {
        val MAP_SETTER =
            TextMapSetter<MutableMap<String, String>> { carrier, key, value ->
                carrier?.put(key, value)
            }
    }
}
