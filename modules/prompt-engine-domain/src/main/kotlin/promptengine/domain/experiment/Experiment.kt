package promptengine.domain.experiment

import promptengine.domain.event.EventContext
import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.InvalidStateTransitionException
import promptengine.domain.shared.PersistenceApi
import java.util.UUID

/**
 * Experiment Bounded Contextの Aggregate Root（設計書§2.5・§4.1・§4.3、ADR-0034）。
 *
 * 不変条件（設計書§4.3）: Variant配分合計=100%（[create]/[updateTraffic]で検証）、
 * Running中のVariant削除禁止（[updateTraffic]はVariant集合の変更を許さず重みのみ
 * 更新可能とすることで、削除を含む集合変更自体を構造的に禁止する）。
 *
 * `Published同時1Version`という`Prompt`の不変条件には一切触れない（ADR-0034決定1）。
 * 各[Variant]は`Approved`/`Published`/`Deprecated`状態の`PromptVersion`を参照でき、
 * `Published`へ遷移させる責務は[promote]経由で`Prompt`側が別途持つ。
 *
 * プライマリコンストラクタは`internal`。新規作成は[create]、永続化層からの復元は
 * [restore]を使うこと（`ReviewCase`と同じ規約、ADR-0006・ADR-0032）。
 */
@ConsistentCopyVisibility
data class Experiment internal constructor(
    val experimentId: UUID,
    val promptKey: PromptKey,
    val type: ExperimentType,
    val status: ExperimentStatus,
    val variants: List<Variant>,
    val trafficPolicy: TrafficPolicy,
    val winnerVariantId: UUID?,
) {
    companion object {
        private const val REQUIRED_WEIGHT_SUM = 100
        private const val MIN_VARIANTS = 2

        /**
         * Experimentを新規作成する（`Draft`状態）。設計書§14にExperiment作成専用の
         * Domain Eventが無い（`ExperimentStarted`以降の4件のみ）ため、[create]自体は
         * イベントを発行しない。
         */
        fun create(
            promptKey: PromptKey,
            type: ExperimentType,
            variants: List<Variant>,
            trafficPolicy: TrafficPolicy,
        ): Experiment {
            requireValidVariants(variants)
            return Experiment(
                experimentId = UUID.randomUUID(),
                promptKey = promptKey,
                type = type,
                status = ExperimentStatus.Draft,
                variants = variants,
                trafficPolicy = trafficPolicy,
                winnerVariantId = null,
            )
        }

        private fun requireValidVariants(variants: List<Variant>) {
            require(variants.size >= MIN_VARIANTS) {
                "Experiment requires at least $MIN_VARIANTS variants: was ${variants.size}"
            }
            val names = variants.map { it.name }
            require(names.size == names.toSet().size) { "Variant names must be unique: $names" }
            val weightSum = variants.sumOf { it.weightPct }
            require(weightSum == REQUIRED_WEIGHT_SUM) {
                "Variant weightPct must sum to $REQUIRED_WEIGHT_SUM: was $weightSum"
            }
        }

        /**
         * 永続化層からの復元専用（`ReviewCase.restore`と同じパターン、ADR-0032）。
         */
        @PersistenceApi
        fun restore(memento: ExperimentMemento): Experiment =
            Experiment(
                memento.experimentId,
                memento.promptKey,
                memento.type,
                memento.status,
                memento.variants,
                memento.trafficPolicy,
                memento.winnerVariantId,
            )
    }

    /** `Draft`→`Running`。 */
    fun start(context: EventContext): Pair<Experiment, ExperimentStarted> {
        assertStatus(ExperimentStatus.Draft, "start")
        val updated = copy(status = ExperimentStatus.Running)
        val event =
            ExperimentStarted(
                eventId = UUID.randomUUID(),
                occurredAt = context.occurredAt,
                aggregateId = experimentId.toString(),
                actor = context.actor,
                traceId = context.traceId,
                payload = ExperimentStarted.Payload(promptKey.value, experimentId),
            )
        return updated to event
    }

    /** `Running`→`Stopped`。 */
    fun stop(context: EventContext): Pair<Experiment, ExperimentStopped> {
        assertStatus(ExperimentStatus.Running, "stop")
        val updated = copy(status = ExperimentStatus.Stopped)
        val event =
            ExperimentStopped(
                eventId = UUID.randomUUID(),
                occurredAt = context.occurredAt,
                aggregateId = experimentId.toString(),
                actor = context.actor,
                traceId = context.traceId,
                payload = ExperimentStopped.Payload(promptKey.value, experimentId),
            )
        return updated to event
    }

    /**
     * 勝者判定を記録する（設計書§4.5 `PromotionService`が統計判定後に呼ぶ、ADR-0034決定5）。
     * `Running`/`Stopped`のいずれからも呼べる（停止後の過去データでの判定も許すため）。
     * 判定自体（統計検定）はAggregateの責務外（[PromotionService]参照）。
     */
    fun declareWinner(
        variantName: String,
        context: EventContext,
    ): Pair<Experiment, ExperimentWinnerDeclared> {
        if (status != ExperimentStatus.Running && status != ExperimentStatus.Stopped) {
            throw InvalidStateTransitionException(status::class.simpleName ?: "Unknown", "declareWinner")
        }
        val winner =
            variants.find { it.name == variantName }
                ?: throw IllegalArgumentException("Variant not found: '$variantName'")
        val updated = copy(winnerVariantId = winner.variantId)
        val event =
            ExperimentWinnerDeclared(
                eventId = UUID.randomUUID(),
                occurredAt = context.occurredAt,
                aggregateId = experimentId.toString(),
                actor = context.actor,
                traceId = context.traceId,
                payload =
                    ExperimentWinnerDeclared.Payload(
                        promptKey.value,
                        experimentId,
                        winner.variantId,
                        winner.name,
                    ),
            )
        return updated to event
    }

    /**
     * 勝者Variantの内容をPublish昇格する（設計書§4.5、ADR-0034決定5・6）。[declareWinner]で
     * 事前に勝者が記録されていることを要求する。`Prompt.publish`の実際の呼び出しは
     * Application層のハンドラが同一トランザクションで行う（`ReviewCase`とADR-0032決定1と
     * 同じ、Aggregate単位トランザクション原則からの意図的かつ限定的な逸脱）。
     */
    fun promote(context: EventContext): Pair<Experiment, ExperimentCompleted> {
        if (status != ExperimentStatus.Running && status != ExperimentStatus.Stopped) {
            throw InvalidStateTransitionException(status::class.simpleName ?: "Unknown", "promote")
        }
        val winnerId = winnerVariantId ?: throw NoWinnerDeclaredException(experimentId)
        val winner = variants.first { it.variantId == winnerId }
        val updated = copy(status = ExperimentStatus.Completed)
        val event =
            ExperimentCompleted(
                eventId = UUID.randomUUID(),
                occurredAt = context.occurredAt,
                aggregateId = experimentId.toString(),
                actor = context.actor,
                traceId = context.traceId,
                payload =
                    ExperimentCompleted.Payload(
                        promptKey.value,
                        experimentId,
                        winner.variantId,
                        winner.name,
                        winner.promptVersionSemVer,
                    ),
            )
        return updated to event
    }

    /**
     * `Running`中のVariant重みを更新する（Canary運用、ADR-0034決定6）。Variant集合
     * （名前の集合）は変更できない。「Running中のVariant削除禁止」（設計書§4.3）を
     * 削除だけでなく追加も含めた集合変更自体の禁止として実装することで、sticky割当
     * （ADR-0034決定3）の前提であるVariant集合の安定性を保つ。
     */
    fun updateTraffic(newVariants: List<Variant>): Experiment {
        assertStatus(ExperimentStatus.Running, "updateTraffic")
        requireValidVariants(newVariants)
        val currentNames = variants.map { it.name }.toSet()
        val newNames = newVariants.map { it.name }.toSet()
        require(currentNames == newNames) {
            "updateTraffic must not change the variant set: current=$currentNames, new=$newNames"
        }
        return copy(variants = newVariants)
    }

    private fun assertStatus(
        expected: ExperimentStatus,
        operation: String,
    ) {
        if (status != expected) {
            throw InvalidStateTransitionException(status::class.simpleName ?: "Unknown", operation)
        }
    }
}

/** [Experiment.promote]が勝者未確定のまま呼ばれた場合の例外（ADR-0034決定5）。 */
class NoWinnerDeclaredException(experimentId: UUID) :
    IllegalStateException("no winner declared for experiment '$experimentId'; call declareWinner first")
