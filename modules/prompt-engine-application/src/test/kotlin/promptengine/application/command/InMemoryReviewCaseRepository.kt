package promptengine.application.command

import promptengine.domain.governance.ReviewCase
import promptengine.domain.governance.ReviewCaseDomainEvent
import promptengine.domain.governance.ReviewCaseRepository
import promptengine.domain.governance.ReviewCaseStatus
import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.SemVer

/** テスト用フェイク。楽観ロック等は検証しない、単純なMapベースの永続化。 */
class InMemoryReviewCaseRepository : ReviewCaseRepository {
    private val store = mutableMapOf<java.util.UUID, ReviewCase>()
    val savedEvents = mutableListOf<ReviewCaseDomainEvent>()

    fun seed(reviewCase: ReviewCase) {
        store[reviewCase.reviewId] = reviewCase
    }

    override fun findInReview(
        promptKey: PromptKey,
        semVer: SemVer,
    ): ReviewCase? =
        store.values.firstOrNull {
            it.promptKey == promptKey && it.semVer == semVer && it.status == ReviewCaseStatus.InReview
        }

    override fun save(
        reviewCase: ReviewCase,
        events: List<ReviewCaseDomainEvent>,
    ): ReviewCase {
        store[reviewCase.reviewId] = reviewCase
        savedEvents += events
        return reviewCase
    }
}
