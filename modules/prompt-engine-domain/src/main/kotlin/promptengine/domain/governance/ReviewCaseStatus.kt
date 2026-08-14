package promptengine.domain.governance

/** `review_cases.status`（設計書§12）。承認・却下いずれも一度到達すると終端（ADR-0032決定4）。 */
sealed class ReviewCaseStatus {
    data object InReview : ReviewCaseStatus()

    data object Approved : ReviewCaseStatus()

    data object Rejected : ReviewCaseStatus()
}
