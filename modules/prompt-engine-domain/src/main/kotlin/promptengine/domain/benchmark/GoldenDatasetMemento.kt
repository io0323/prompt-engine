package promptengine.domain.benchmark

import promptengine.domain.prompt.PromptKey
import java.util.UUID

/**
 * [GoldenDataset]の永続化層からの復元用Memento（`ExperimentMemento`と同じパターン、ADR-0035）。
 * `golden_datasets`テーブルに`row_version`列を持たないため、`Experiment`と同様
 * 楽観ロックは対象外とする（同時更新は考慮しない。単一の管理者操作を前提とするため）。
 */
data class GoldenDatasetMemento(
    val datasetId: UUID,
    val promptKey: PromptKey,
    val name: String,
    val description: String?,
    val items: List<GoldenDatasetItem>,
)
