package promptengine.infrastructure.observability

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import promptengine.domain.pipeline.PipelineTracer

/**
 * [PipelineTracer]のOpenTelemetry実装（設計書§2.15、Issue #38、ADR-0027決定2）。
 *
 * [PipelineTracer.withSpan]は呼出しごとに独立しており、Pipeline 1回の`run()`内で12回
 * 呼ばれる各Stageの間で明示的なContextの受け渡しが無い（インターフェース自体はP8で
 * 確定済みであり本フェーズでは変更しない）。そのため、[traceId]（アプリ側の相関ID文字列）
 * からSHA-256で決定的にOTelの128bit TraceId・64bit SpanId（後者は「このリクエストの
 * 仮想ルート」を表す固定値）を導出し、同一[traceId]の全Stage呼び出しが同じOTel Traceの
 * 子Spanとして記録されるようにする。スレッド間伝播や有効期限管理を要する状態（Map等）を
 * 一切持たないため、メモリリークやスレッド安全性の懸念が無い。
 *
 * 各Spanには`pe.trace_id`属性として[traceId]をそのまま載せる。OTel生成のTraceIdは
 * ログ・監査記録が使う相関ID（[traceId]）とは別物であるため、両者を突き合わせられるように
 * するための冗長化。
 *
 * [traceId]からのSpanContext導出自体は[deterministicSpanContext]（同パッケージ、
 * `OpenTelemetryTraceContextPropagator`と共用）に委ねる。
 */
class OpenTelemetryPipelineTracer(
    private val tracer: Tracer,
) : PipelineTracer {
    @Suppress("TooGenericExceptionCaught")
    override fun <T> withSpan(
        stageName: String,
        traceId: String,
        block: () -> T,
    ): T {
        val span =
            tracer.spanBuilder(stageName)
                .setParent(rootContextFor(traceId))
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute(TRACE_ID_ATTRIBUTE, traceId)
                .startSpan()
        return span.makeCurrent().use {
            try {
                block()
            } catch (e: Exception) {
                span.recordException(e)
                span.setStatus(StatusCode.ERROR)
                throw e
            } finally {
                span.end()
            }
        }
    }

    /**
     * [traceId]から決定的に導出した非記録の[SpanContext][io.opentelemetry.api.trace.SpanContext]を
     * 親に持つ[Context]を返す。`SpanContext.createFromRemoteParent`はこのプロセス自身が開始した
     * Spanではない（実際には仮想的な合成値）ことを示すために使う（OTelのAPI上、自プロセス起源
     * でない親を表現する標準的な方法）。
     */
    private fun rootContextFor(traceId: String): Context =
        Context.root().with(Span.wrap(deterministicSpanContext(traceId)))

    private companion object {
        val TRACE_ID_ATTRIBUTE: AttributeKey<String> = AttributeKey.stringKey("pe.trace_id")
    }
}
