package promptengine.infrastructure.persistence

import promptengine.domain.experiment.ExperimentStatus
import promptengine.domain.experiment.ExperimentType

/**
 * DB行の文字列表現とdomain型（[ExperimentStatus] / [ExperimentType]）の相互変換
 * （[ReviewCaseStatusCodec.kt]と同じ形、ADR-0034）。
 */
internal fun ExperimentStatus.toDbValue(): String =
    when (this) {
        ExperimentStatus.Draft -> "Draft"
        ExperimentStatus.Running -> "Running"
        ExperimentStatus.Stopped -> "Stopped"
        ExperimentStatus.Completed -> "Completed"
    }

internal fun experimentStatusFromDbValue(value: String): ExperimentStatus =
    when (value) {
        "Draft" -> ExperimentStatus.Draft
        "Running" -> ExperimentStatus.Running
        "Stopped" -> ExperimentStatus.Stopped
        "Completed" -> ExperimentStatus.Completed
        else -> error("unknown ExperimentStatus value: $value")
    }

internal fun ExperimentType.toDbValue(): String = name

internal fun experimentTypeFromDbValue(value: String): ExperimentType = ExperimentType.valueOf(value)
