package promptengine.domain.experiment

import promptengine.domain.event.DomainEvent
import promptengine.domain.shared.SemVer
import java.time.Instant
import java.util.UUID

/**
 * Experiment Aggregateが発行するDomain Eventの共通基底（設計書§14、ADR-0034）。
 * [promptengine.domain.governance.ReviewCaseDomainEvent]と同じ形。
 */
sealed class ExperimentDomainEvent : DomainEvent {
    override val eventType: String get() = this::class.simpleName ?: "Unknown"
    override val aggregateType: String get() = "Experiment"
}

/** 実験開始（設計書§14「実験管理」）。 */
data class ExperimentStarted(
    override val eventId: UUID,
    override val occurredAt: Instant,
    override val aggregateId: String,
    override val actor: String,
    override val traceId: String,
    override val payload: Payload,
) : ExperimentDomainEvent() {
    data class Payload(val promptKey: String, val experimentId: UUID)
}

/** 実験停止（設計書§14「実験管理」）。 */
data class ExperimentStopped(
    override val eventId: UUID,
    override val occurredAt: Instant,
    override val aggregateId: String,
    override val actor: String,
    override val traceId: String,
    override val payload: Payload,
) : ExperimentDomainEvent() {
    data class Payload(val promptKey: String, val experimentId: UUID)
}

/**
 * 勝者判定（設計書§14「勝者昇格トリガ」、発火元「Experiment Engine」）。統計判定
 * （[promptengine.domain.experiment.PromotionService]、ADR-0034決定5）を経て
 * [Experiment.declareWinner]が返す。判定結果自体（p値・サンプル数）はpayloadに含めない
 * （`evaluation_records`から再計算可能であり、イベントは判定を行ったという事実と
 * 対象Variantのみを運ぶ。[promptengine.domain.evaluation.PromptEvaluationCompletedEvent]と
 * 同じ「生データはpayloadに持たせない」設計思想）。
 */
data class ExperimentWinnerDeclared(
    override val eventId: UUID,
    override val occurredAt: Instant,
    override val aggregateId: String,
    override val actor: String,
    override val traceId: String,
    override val payload: Payload,
) : ExperimentDomainEvent() {
    data class Payload(
        val promptKey: String,
        val experimentId: UUID,
        val winnerVariantId: UUID,
        val winnerVariantName: String,
    )
}

/**
 * 実験完了（設計書§14「実験履歴」）。[Experiment.promote]が勝者Variantを
 * `Prompt.publish`させた直後に発火する（ADR-0032決定1と同じ、複数Aggregateを
 * 同一トランザクションで整合させるパターン）。
 */
data class ExperimentCompleted(
    override val eventId: UUID,
    override val occurredAt: Instant,
    override val aggregateId: String,
    override val actor: String,
    override val traceId: String,
    override val payload: Payload,
) : ExperimentDomainEvent() {
    data class Payload(
        val promptKey: String,
        val experimentId: UUID,
        val winnerVariantId: UUID,
        val winnerVariantName: String,
        val promotedSemVer: SemVer,
    )
}
