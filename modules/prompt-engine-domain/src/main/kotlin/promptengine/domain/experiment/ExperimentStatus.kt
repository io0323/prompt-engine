package promptengine.domain.experiment

/**
 * Experimentのライフサイクル状態（設計書§12 `experiments.status`、ADR-0034）。
 *
 * `Prompt`の[promptengine.domain.prompt.LifecycleState]と同じStateパターン
 * （設計書§3.5）を踏襲するが、遷移が単純（Draft→Running→Stopped、Completedは
 * [promptengine.domain.experiment.Experiment.promote]専用の終端）なため、
 * 各状態が遷移メソッドを持つのではなくAggregate側（[Experiment]）でガード条件を
 * 判定する（[promptengine.domain.governance.ReviewCaseStatus]と同じ「単純な状態には
 * 判定ロジックを持たせない」方針）。
 */
sealed class ExperimentStatus {
    data object Draft : ExperimentStatus()

    data object Running : ExperimentStatus()

    data object Stopped : ExperimentStatus()

    data object Completed : ExperimentStatus()
}
