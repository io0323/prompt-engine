package promptengine.application.command

import promptengine.domain.experiment.Experiment
import promptengine.domain.experiment.ExperimentDomainEvent
import promptengine.domain.experiment.ExperimentRepository
import promptengine.domain.experiment.ExperimentStatus
import promptengine.domain.prompt.PromptKey
import java.util.UUID

/** テスト用フェイク。楽観ロック等は検証しない、単純なMapベースの永続化（ADR-0034）。 */
class InMemoryExperimentRepository : ExperimentRepository {
    private val store = mutableMapOf<UUID, Experiment>()
    val savedEvents = mutableListOf<ExperimentDomainEvent>()

    fun seed(experiment: Experiment) {
        store[experiment.experimentId] = experiment
    }

    override fun findById(experimentId: UUID): Experiment? = store[experimentId]

    override fun findActiveByPrompt(promptKey: PromptKey): List<Experiment> =
        store.values.filter { it.promptKey == promptKey && it.status == ExperimentStatus.Running }

    override fun save(
        experiment: Experiment,
        events: List<ExperimentDomainEvent>,
    ): Experiment {
        store[experiment.experimentId] = experiment
        savedEvents += events
        return experiment
    }
}
