package promptengine.domain.experiment

import java.util.UUID

/** 指定した[experimentId]の`Experiment`が存在しない（ADR-0034）。 */
class ExperimentNotFoundException(experimentId: UUID) :
    NoSuchElementException("Experiment not found: '$experimentId'")
