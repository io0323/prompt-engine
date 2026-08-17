package promptengine.domain.experiment

import promptengine.domain.shared.SemVer
import java.util.UUID

/**
 * Experiment Aggregate内のEntity（設計書§4.3・§12 `variants`、ADR-0034）。
 *
 * [variantId]は永続化層のFK（`evaluation_records.variant_id`/`execution_logs.variant_id`/
 * `PromptExecutedEvent.Payload.variantId`）として使う内部識別子。呼出元・REST APIは
 * [name]を主キーとして扱う（ADR-0034決定8。`variant_id`はDBの外に出さない）。
 *
 * [promptVersionSemVer]が指す`PromptVersion`は`Approved`/`Published`/`Deprecated`の
 * いずれかである必要がある（`Draft`/`InReview`参照は拒否、ADR-0034決定1）。この検証は
 * [Experiment.create]・[promptengine.domain.pipeline.ExperimentResolvedVersion]の
 * ファクトリの責務であり、[Variant]自身は状態を知らない（Variantは`SemVer`のみを保持し、
 * `PromptVersion`そのものを持たないため）。
 */
data class Variant(
    val variantId: UUID,
    val name: String,
    val promptVersionSemVer: SemVer,
    val weightPct: Int,
) {
    init {
        require(name.isNotBlank()) { "Variant name must not be blank" }
        require(weightPct in 0..MAX_WEIGHT_PCT) { "weightPct must be within 0..$MAX_WEIGHT_PCT: was $weightPct" }
    }

    private companion object {
        const val MAX_WEIGHT_PCT = 100
    }
}
