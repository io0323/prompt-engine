package promptengine.infrastructure.observability

import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

private const val TRACE_ID_BYTES = 16
private const val SPAN_ID_BYTES = 8
private val HEX_FORMAT: HexFormat = HexFormat.of()

/**
 * [traceId]（アプリ側の相関ID文字列）からSHA-256で決定的にOTelの128bit TraceId・64bit SpanIdを
 * 導出する（[OpenTelemetryPipelineTracer]・`OpenTelemetryTraceContextPropagator`共通、
 * Issue #38、ADR-0027決定2）。同一[traceId]から呼べば常に同じ[SpanContext]が得られるため、
 * スレッド間伝播や有効期限管理を要する状態（Map等）を一切持たずに、Pipeline 1回の`run()`内の
 * 全Span・APAP呼出への`traceparent`ヘッダが同一OTel Traceの下へ相関する。
 */
internal fun deterministicSpanContext(traceId: String): SpanContext {
    val digest = MessageDigest.getInstance("SHA-256").digest(traceId.toByteArray(StandardCharsets.UTF_8))
    val otelTraceId = HEX_FORMAT.formatHex(digest, 0, TRACE_ID_BYTES)
    val otelSpanId = HEX_FORMAT.formatHex(digest, TRACE_ID_BYTES, TRACE_ID_BYTES + SPAN_ID_BYTES)
    return SpanContext.createFromRemoteParent(
        otelTraceId,
        otelSpanId,
        TraceFlags.getSampled(),
        TraceState.getDefault(),
    )
}
