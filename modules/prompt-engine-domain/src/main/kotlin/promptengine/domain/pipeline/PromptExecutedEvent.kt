package promptengine.domain.pipeline

import promptengine.domain.evaluation.ExecutionStatus
import promptengine.domain.event.DomainEvent
import promptengine.domain.shared.SemVer
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Pipeline Full-execution完了（設計書§14 `PromptExecuted`、発火元「Pipeline Orchestrator」、
 * 購読先「Evaluation Engine, Monitoring, Audit」）。
 *
 * `Prompt`/`PromptVersion` Aggregate自身が発行する[promptengine.domain.prompt.PromptDomainEvent]系
 * とは異なり、発火元がPipeline Orchestratorであるため`domain.pipeline`に置く。
 * [payload]は秘密情報・生のprompt/response内容を含まない構造的な要約のみ
 * （[AuditRecord][promptengine.domain.audit.AuditRecord]と同じ設計思想）。
 */
data class PromptExecutedEvent(
    override val eventId: UUID,
    override val occurredAt: Instant,
    override val aggregateId: String,
    override val actor: String,
    override val traceId: String,
    override val payload: Payload,
) : DomainEvent {
    override val eventType: String = "PromptExecuted"
    override val aggregateType: String = "Prompt"

    /**
     * 設計書§14の購読先「Evaluation Engine, Monitoring, Audit」が必要とする最小集合。
     *
     * P10a時点は`promptKey`/`inputTokens`/`outputTokens`/`retryCount`のみだったが、
     * これでは`execution_logs`（`version_id`・`latency_ms`・`status`・`cost`が必須、設計書§12）も
     * Evaluation のLatency/Cost指標（設計書§2.12）も算出できないため、P10bで
     * [semVer]/[latencyMs]/[costPerToken]/[status]を追加した（ADR-0026決定3）。
     *
     * [semVer]は購読側が`prompts.prompt_key` + `prompt_versions.version`から
     * `prompt_versions.version_id`（永続化サロゲートUUID）を解決するために必要
     * （イベントは業務キーしか運ばない、ADR-0025決定1）。
     *
     * [latencyMs]はStage 9（Execution）の実測時間（設計書§2.12「Latency | Execution Stage実測」）。
     *
     * [costPerToken]は実行時点の[promptengine.domain.optimization.ModelProfile.costPerToken]。
     * 購読側が後からModelProfileを引き直すと単価改定後の再評価で当時と違うコストが出るため、
     * 実行時点の単価をイベント自身に載せる（ADR-0026決定3）。
     *
     * [variantId]はM2-4a（ADR-0034決定4）で追加。Experiment経由の実行（`PipelineContext.
     * experimentVariantId`が非null）のみ非NULL。通常経路の実行は`null`のまま
     * （`evaluation_records.variant_id`/`execution_logs.variant_id`の由来）。
     */
    data class Payload(
        val promptKey: String,
        val semVer: SemVer,
        val inputTokens: Int,
        val outputTokens: Int,
        val retryCount: Int,
        val latencyMs: Long,
        val costPerToken: BigDecimal,
        val status: ExecutionStatus,
        val variantId: UUID? = null,
    )
}
