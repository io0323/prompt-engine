package promptengine.domain.benchmark

import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.PersistenceApi
import java.util.UUID

/**
 * Golden Dataset Bounded ContextのAggregate Root（設計書§2.12・§4.3・§12、ADR-0035）。
 *
 * `Experiment`とは無関係の独立したAggregateである（ADR-0035決定1）。1つの[GoldenDataset]は
 * 複数の`Benchmark`実行から参照され得る（Version比較のたびに複製する必要をなくすため、
 * ADR-0035決定2）。
 *
 * 不変条件（設計書§4.3）: item 1件以上。[items]の追加・削除に対する状態遷移制約は無い
 * （`Experiment`のVariantと異なりRunning中の変更禁止のような概念自体が存在しない。
 * データセットはいつでも編集できる静的なデータである）。
 *
 * プライマリコンストラクタは`internal`。新規作成は[create]、永続化層からの復元は[restore]を
 * 使うこと（`Experiment`/`ReviewCase`と同じ規約、ADR-0006・ADR-0032・ADR-0034）。
 */
@ConsistentCopyVisibility
data class GoldenDataset internal constructor(
    val datasetId: UUID,
    val promptKey: PromptKey,
    val name: String,
    val description: String?,
    val items: List<GoldenDatasetItem>,
) {
    companion object {
        fun create(
            promptKey: PromptKey,
            name: String,
            description: String?,
            items: List<GoldenDatasetItem>,
        ): GoldenDataset {
            require(name.isNotBlank()) { "GoldenDataset name must not be blank" }
            require(items.isNotEmpty()) { "GoldenDataset requires at least 1 item" }
            return GoldenDataset(UUID.randomUUID(), promptKey, name, description, items)
        }

        /** 永続化層からの復元専用（`Experiment.restore`と同じパターン、ADR-0034）。 */
        @PersistenceApi
        fun restore(memento: GoldenDatasetMemento): GoldenDataset =
            GoldenDataset(memento.datasetId, memento.promptKey, memento.name, memento.description, memento.items)
    }

    /**
     * データセットの内容を入れ替える（`Experiment.updateTraffic`と同じ形。プライマリ
     * コンストラクタが`internal`のため`copy()`もモジュール外から呼べない
     * （`@ConsistentCopyVisibility`）。データセットは静的なデータであり`Variant`集合の
     * ような状態遷移制約は無いため、[updateItems]は状態を問わずいつでも呼べる）。
     */
    fun updateItems(newItems: List<GoldenDatasetItem>): GoldenDataset {
        require(newItems.isNotEmpty()) { "GoldenDataset requires at least 1 item" }
        return copy(items = newItems)
    }
}
