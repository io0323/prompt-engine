package promptengine.domain.observability

/**
 * W3C Trace Context（`traceparent`ヘッダ）の生成の抽象（設計書§2.15「APAP呼出へContext伝播」、
 * Issue #38、ADR-0027決定2）。
 *
 * 実APAP接続（`ApapExecutionAdapter`）は[promptengine.domain.execution.ExecutionAdapter]の
 * KDoc通りM2で追加予定であり、本リポジトリ（M1）には出力先となる実HTTP呼出経路が
 * まだ存在しない（唯一の実装`FakeExecutionAdapter`は固定応答を返すのみで実際のネットワーク
 * 呼出を行わない）。そのため、この抽象は「M2のAPAP Adapterが呼び出せば
 * [promptengine.domain.pipeline.PipelineTracer]が生成するSpanと同じOTel Traceへ相関する
 * `traceparent`ヘッダ値を得られる」契約のみを準備し、[promptengine.domain.execution.ExecutionAdapter]
 * インターフェース自体（M1で確定済み、変更にはADRが必要）は変更しない。
 */
interface TraceContextPropagator {
    /** [traceId]（Pipeline全体の相関ID）に対応するW3C `traceparent`ヘッダ値を返す。 */
    fun traceparentFor(traceId: String): String
}
