package promptengine.infrastructure.persistence

import promptengine.domain.shared.PublicationState

/**
 * DB行の文字列表現と [PublicationState] の相互変換（Template/Fragment共通、ADR-0008）。
 * `internal`: `prompt-engine-infrastructure` モジュール内の永続化コードのみが使う。
 */
internal fun PublicationState.toDbValue(): String =
    when (this) {
        PublicationState.Draft -> "Draft"
        PublicationState.Published -> "Published"
        PublicationState.Archived -> "Archived"
    }

internal fun publicationStateFromDbValue(value: String): PublicationState =
    when (value) {
        "Draft" -> PublicationState.Draft
        "Published" -> PublicationState.Published
        "Archived" -> PublicationState.Archived
        else -> error("unknown PublicationState value: $value")
    }
