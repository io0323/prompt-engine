package promptengine.engine.pipeline

import promptengine.domain.pipeline.PipelineTracer

/**
 * [PipelineTracer]の既定実装（何もしない）。実際にOpenTelemetryへ接続する実装
 * （`OpenTelemetryPipelineTracer`、`prompt-engine-infrastructure`）を配線するまでの
 * 既定値、およびテストでの利用を想定する。
 */
class NoopPipelineTracer : PipelineTracer {
    override fun <T> withSpan(
        stageName: String,
        traceId: String,
        block: () -> T,
    ): T = block()
}
