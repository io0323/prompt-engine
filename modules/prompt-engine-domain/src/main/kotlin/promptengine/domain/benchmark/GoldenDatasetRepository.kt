package promptengine.domain.benchmark

import promptengine.domain.prompt.PromptKey
import java.util.UUID

/**
 * [GoldenDataset]の永続化インターフェース（ADR-0035）。
 * 実装は`prompt-engine-infrastructure`（`JdbcGoldenDatasetRepository`）。
 */
interface GoldenDatasetRepository {
    fun findById(datasetId: UUID): GoldenDataset?

    /** 対象Promptに紐づく全Golden Datasetを検索する（データセット選択UI・Benchmark作成時に使う）。 */
    fun findByPromptKey(promptKey: PromptKey): List<GoldenDataset>

    fun save(dataset: GoldenDataset): GoldenDataset
}
