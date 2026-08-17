package promptengine.domain.experiment

import promptengine.domain.prompt.PromptKey
import java.util.UUID

/**
 * [Experiment]の永続化層からの復元用Memento（`ReviewCaseMemento`と同じパターン、ADR-0034）。
 * `experiments`テーブルに`row_version`列が無いため、`Prompt`とは異なり`rowVersion`は持たない
 * （`review_cases`と同じ、同時更新は`SELECT ... FOR UPDATE`で直列化する）。
 */
data class ExperimentMemento(
    val experimentId: UUID,
    val promptKey: PromptKey,
    val type: ExperimentType,
    val status: ExperimentStatus,
    val variants: List<Variant>,
    val trafficPolicy: TrafficPolicy,
    val winnerVariantId: UUID?,
)
