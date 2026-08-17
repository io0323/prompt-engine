package promptengine.domain.experiment

import promptengine.domain.prompt.PromptKey
import java.util.UUID

/**
 * [Experiment]の永続化インターフェース（設計書§3.4疑似コード
 * `ExperimentRepository{ save(e): void; findActiveByPrompt(k): list<Experiment> }`、ADR-0034）。
 * 実装は`prompt-engine-infrastructure`（`JdbcExperimentRepository`）。
 */
interface ExperimentRepository {
    fun findById(experimentId: UUID): Experiment?

    /** `Running`状態のExperimentを対象Promptについて検索する（[ExperimentVariantResolver]が使う）。 */
    fun findActiveByPrompt(promptKey: PromptKey): List<Experiment>

    /**
     * 現在状態の保存と[events]のEvent Store追記を同一トランザクションで行う
     * （`PromptRepository`/`ReviewCaseRepository`と同じ契約、ADR-0006）。
     */
    fun save(
        experiment: Experiment,
        events: List<ExperimentDomainEvent> = emptyList(),
    ): Experiment
}
