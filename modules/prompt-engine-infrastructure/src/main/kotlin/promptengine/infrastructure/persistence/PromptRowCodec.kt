package promptengine.infrastructure.persistence

import promptengine.domain.prompt.LifecycleState
import promptengine.domain.shared.SemVer

/**
 * DB行の文字列表現とdomain型（[LifecycleState] / [SemVer]）の相互変換。
 * `internal`: `prompt-engine-infrastructure` モジュール内の永続化コードのみが使う。
 */
internal fun LifecycleState.toDbValue(): String =
    when (this) {
        LifecycleState.Draft -> "Draft"
        LifecycleState.InReview -> "InReview"
        LifecycleState.Approved -> "Approved"
        LifecycleState.Published -> "Published"
        LifecycleState.Deprecated -> "Deprecated"
        LifecycleState.Archived -> "Archived"
    }

internal fun lifecycleStateFromDbValue(value: String): LifecycleState =
    when (value) {
        "Draft" -> LifecycleState.Draft
        "InReview" -> LifecycleState.InReview
        "Approved" -> LifecycleState.Approved
        "Published" -> LifecycleState.Published
        "Deprecated" -> LifecycleState.Deprecated
        "Archived" -> LifecycleState.Archived
        else -> error("unknown LifecycleState value: $value")
    }

private const val SEM_VER_PART_COUNT = 3

internal fun parseSemVer(value: String): SemVer {
    val parts = value.split(".")
    require(parts.size == SEM_VER_PART_COUNT) { "invalid SemVer value: $value" }
    return SemVer(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
}
