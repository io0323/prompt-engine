package promptengine.domain.evaluation

import promptengine.domain.execution.Usage
import promptengine.domain.shared.Cost
import promptengine.domain.shared.LatencyMs
import promptengine.domain.shared.SemVer
import java.time.Instant
import java.util.UUID

/**
 * `execution_logs`（設計書§12・§2.15 Monitoring）の1行（ADR-0026決定1）。
 *
 * 唯一の書き込み経路は`PromptExecuted`を購読する`ExecutionLogSubscriber`
 * （`prompt-engine-infrastructure`）であり、Pipeline Stage 12（Audit）は書かない
 * （ADR-0026決定1: Stageは「既存Engineへの薄い委譲」に留める）。
 *
 * [eventId]が`execution_logs.event_id`（UNIQUE、V13）に対応し、同一イベントの再配信で
 * 行が二重にならないことを保証する（ADR-0025決定8）。
 *
 * [callerSystem]（`execution_logs.caller_system`）は本来「どのクライアントシステムが
 * 呼び出したか」を表す列だが、M1時点でPipelineへ呼出元識別情報を伝搬する経路が存在しない。
 * イベントの`actor`を暫定的に写して埋める（ADR-0026「既知の限界」）。
 *
 * `execution_logs.version_id`（サロゲートUUID）への解決は[EvaluationRecord]と同じく
 * Repository実装が[promptKey] + [semVer]から行う。
 */
data class ExecutionLogEntry(
    val eventId: UUID,
    val promptKey: String,
    val semVer: SemVer,
    val callerSystem: String,
    val traceId: String,
    val latency: LatencyMs,
    val usage: Usage,
    val cost: Cost,
    val status: ExecutionStatus,
    val executedAt: Instant,
) {
    init {
        require(promptKey.isNotBlank()) { "promptKey must not be blank" }
        require(callerSystem.isNotBlank()) { "callerSystem must not be blank" }
    }
}
