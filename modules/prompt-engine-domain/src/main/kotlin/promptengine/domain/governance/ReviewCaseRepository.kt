package promptengine.domain.governance

import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.SemVer

/** `ReviewCase` Aggregateの永続化インターフェース。実装は `prompt-engine-infrastructure`（ADR-0032）。 */
interface ReviewCaseRepository {
    /**
     * 指定した`PromptKey`・`SemVer`について現在`InReview`の`ReviewCase`を返す（無ければ`null`）。
     * ADR-0032決定4により1つのVersionに対して複数の`ReviewCase`行が存在しうるが、
     * `InReview`は`Prompt`側のLifecycleStateガード（同時に1つの提出のみ許す）により高々1件。
     * `approve`/`reject`エンドポイント（設計書§13.1）は`reviewId`をパスに持たないため、
     * この解決方法が唯一の入口になる。
     */
    fun findInReview(
        promptKey: PromptKey,
        semVer: SemVer,
    ): ReviewCase?

    /**
     * 現在状態（RDB投影）の保存と、[events] のEvent Store追記を同一トランザクションで行う
     * （P2の`PromptRepository.save`パターンを踏襲、ADR-0032）。
     */
    fun save(
        reviewCase: ReviewCase,
        events: List<ReviewCaseDomainEvent> = emptyList(),
    ): ReviewCase
}
