package promptengine.domain.prompt

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import promptengine.domain.event.EventContext
import promptengine.domain.shared.SemVer
import java.time.Instant

/**
 * Prompt Aggregate のテスト。
 * 設計書 §2.5 遷移表8行（(新規)→Draft を除く）と §4.3 不変条件表の Prompt 行を
 * 1つずつ対応させる。対応表はコミットメッセージ / レビュー依頼時に別途提示する。
 *
 * ADR-0004 により、submitForReview / reject / approve はReviewCase（Governance、
 * 本フェーズ未実装）がイベントの発火元であるため、Prompt Aggregateからは
 * LifecycleStateの遷移のみ行いイベントは返さない。withdraw / discard は
 * Prompt Aggregate自身が発火元の新規イベントを返す。
 *
 * ADR-0005 により、publish/rollback は既存Publishedを自動的にDeprecated
 * (reason=SUPERSEDED)へ遷移させるアトミック操作であるため、イベントはリストで返る。
 */
class PromptTest {
    private val key = PromptKey("support/faq-answer")
    private val content = PromptContent("Answer: {{question}}")
    private val context =
        EventContext(actor = "user:alice", traceId = "trace-1", occurredAt = Instant.parse("2026-01-01T00:00:00Z"))

    // ---- (新規)→Draft: create / newVersion ----

    @Test
    fun `create はDraft状態のVersionを1つ持つPromptを生成しPromptCreatedを発行する`() {
        val (prompt, event) = Prompt.create(key, NewPromptVersion(SemVer(0, 1, 0), content), context)

        prompt.key shouldBe key
        prompt.versions.size shouldBe 1
        prompt.versions.single().state shouldBe LifecycleState.Draft

        event.eventId shouldNotBe null
        event.eventType shouldBe "PromptCreated"
        event.occurredAt shouldBe context.occurredAt
        event.aggregateType shouldBe "Prompt"
        event.aggregateId shouldBe key.value
        event.actor shouldBe context.actor
        event.traceId shouldBe context.traceId
        event.payload.promptKey shouldBe key.value
        event.payload.semVer shouldBe SemVer(0, 1, 0)
    }

    @Test
    fun `newVersion は既存Promptに新しいDraft Versionを追加しPromptVersionCreatedを発行する`() {
        val (created, _) = Prompt.create(key, NewPromptVersion(SemVer(0, 1, 0), content), context)

        val (prompt, event) =
            created.newVersion(NewPromptVersion(SemVer(0, 2, 0), PromptContent("Answer(v2): {{question}}")), context)

        prompt.versions.map { it.semVer } shouldBe listOf(SemVer(0, 1, 0), SemVer(0, 2, 0))
        prompt.versions.last().state shouldBe LifecycleState.Draft
        event.eventType shouldBe "PromptVersionCreated"
        event.payload.semVer shouldBe SemVer(0, 2, 0)
    }

    @Test
    fun `newVersion は既存と同じSemVerを指定するとIllegalArgumentExceptionを投げる`() {
        val (created, _) = createDraft()

        shouldThrow<IllegalArgumentException> {
            created.newVersion(NewPromptVersion(SemVer(0, 1, 0), PromptContent("duplicate")), context)
        }
    }

    // ---- Draft→InReview: submitForReview（ガード: Validation合格）----
    // ADR-0004: Prompt Aggregateはイベントを発行しない（ReviewCaseが PromptReviewRequested を発行）。

    @Test
    fun `submitForReview はValidation合格時にDraftからInReviewへ遷移する イベントは発行しない`() {
        val (created, _) = createDraft()

        val prompt = created.submitForReview(SemVer(0, 1, 0), validationPassed = true)

        prompt.versions.single().state shouldBe LifecycleState.InReview
    }

    @Test
    fun `submitForReview はValidation不合格の場合InvalidStateTransitionExceptionを投げる`() {
        val (created, _) = createDraft()

        shouldThrow<InvalidStateTransitionException> {
            created.submitForReview(SemVer(0, 1, 0), validationPassed = false)
        }
    }

    // ---- InReview→Draft: reject / withdraw（ガードなし）----
    // ADR-0004: reject はReviewCaseが PromptRejected を発行するためイベントなし。
    // withdraw は著者自身の操作でありPrompt Aggregateが PromptWithdrawn を発行する。

    @Test
    fun `reject はInReviewからDraftへ差し戻す イベントは発行しない`() {
        val inReview = createInReview()

        val prompt = inReview.reject(SemVer(0, 1, 0))

        prompt.versions.single().state shouldBe LifecycleState.Draft
    }

    @Test
    fun `reject はDraft状態のVersionに対して実行するとInvalidStateTransitionExceptionを投げる`() {
        val (created, _) = createDraft()

        shouldThrow<InvalidStateTransitionException> {
            created.reject(SemVer(0, 1, 0))
        }
    }

    @Test
    fun `withdraw はInReviewからDraftへ差し戻しPromptWithdrawnを発行する`() {
        val inReview = createInReview()

        val (prompt, event) = inReview.withdraw(SemVer(0, 1, 0), context)

        prompt.versions.single().state shouldBe LifecycleState.Draft
        event.eventType shouldBe "PromptWithdrawn"
        event.aggregateId shouldBe key.value
        event.payload.semVer shouldBe SemVer(0, 1, 0)
    }

    // ---- InReview→Approved: approve（ガード: 必要承認数充足）----
    // ADR-0004: ReviewCaseが PromptApproved を発行するためイベントなし。

    @Test
    fun `approve は必要承認数を満たすとApprovedへ遷移する イベントは発行しない`() {
        val inReview = createInReview()

        val prompt = inReview.approve(SemVer(0, 1, 0), approvalCount = 1, requiredApprovalCount = 1)

        prompt.versions.single().state shouldBe LifecycleState.Approved
    }

    @Test
    fun `approve は承認数が不足しているとInvalidStateTransitionExceptionを投げる`() {
        val inReview = createInReview()

        shouldThrow<InvalidStateTransitionException> {
            inReview.approve(SemVer(0, 1, 0), approvalCount = 0, requiredApprovalCount = 1)
        }
    }

    // ---- Approved→Published: publish（ガード: 依存先が全てPublished）----
    // ADR-0005: 他にPublished中のVersionがあれば自動的にDeprecated(reason=SUPERSEDED)へ遷移させる。

    @Test
    fun `publish は他にPublished中のVersionが無い場合PromptPublishedのみを発行する`() {
        val approved = createApproved()

        val (prompt, events) = approved.publish(SemVer(0, 1, 0), allDependenciesPublished = true, context)

        prompt.versions.single().state shouldBe LifecycleState.Published
        events.map { it.eventType } shouldBe listOf("PromptPublished")
    }

    @Test
    fun `publish は依存先が未Publishの場合InvalidStateTransitionExceptionを投げる`() {
        val approved = createApproved()

        shouldThrow<InvalidStateTransitionException> {
            approved.publish(SemVer(0, 1, 0), allDependenciesPublished = false, context)
        }
    }

    @Test
    fun `publish は既存Publishedがある場合そのVersionをDeprecated reason SUPERSEDEDへ遷移させ両方のイベントを発行する`() {
        val (publishedV1, _) = createPublished()
        val v2 = approvedSecondVersion(publishedV1)

        val (prompt, events) = v2.publish(SemVer(0, 2, 0), allDependenciesPublished = true, context)

        prompt.versions.first { it.semVer == SemVer(0, 1, 0) }.state shouldBe LifecycleState.Deprecated
        prompt.versions.first { it.semVer == SemVer(0, 2, 0) }.state shouldBe LifecycleState.Published
        prompt.versions.count { it.state == LifecycleState.Published } shouldBe 1

        val deprecatedEvent = events.filterIsInstance<PromptDeprecated>().single()
        deprecatedEvent.payload.semVer shouldBe SemVer(0, 1, 0)
        deprecatedEvent.payload.reason shouldBe DeprecationReason.SUPERSEDED
        deprecatedEvent.payload.recommendedReplacement shouldBe VersionRef.Fixed(SemVer(0, 2, 0))

        val publishedEvent = events.filterIsInstance<PromptPublished>().single()
        publishedEvent.payload.semVer shouldBe SemVer(0, 2, 0)
    }

    // ---- 不変条件: Published は同時に1Version ----

    @Test
    fun `versionsに2つのPublished状態Versionを直接構築しようとするとIllegalArgumentExceptionを投げる`() {
        val publishedV1 = PromptVersion(SemVer(0, 1, 0), content, state = LifecycleState.Published)
        val publishedV2 = PromptVersion(SemVer(0, 2, 0), content, state = LifecycleState.Published)

        shouldThrow<IllegalArgumentException> {
            Prompt(key, listOf(publishedV1, publishedV2))
        }
    }

    @Test
    fun `versionsが空のPromptを直接構築しようとするとIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> {
            Prompt(key, emptyList())
        }
    }

    // ---- Published→Published: rollback ----
    // ガードの解釈については Prompt.rollback() の KDoc を参照
    // （§2.5は「対象Versionが存在」としか書いていないが、ここでは
    // 「過去にPublishedだった＝現在Deprecated状態のVersion」であることまで要求し、
    // Archivedは除外する）。
    // publishと同様、rollback時点のPublished Versionを自動的にDeprecated(SUPERSEDED)へ
    // 遷移させるsupersede操作として揃える。

    @Test
    fun `rollback はDeprecatedになっている過去のPublished Versionへ再度Publishしもう一方をDeprecated SUPERSEDEDにし両方のイベントを発行する`() {
        val (publishedV1, _) = createPublished()
        val v2 = approvedSecondVersion(publishedV1)
        val (publishedV2, _) = v2.publish(SemVer(0, 2, 0), allDependenciesPublished = true, context)
        // この時点で v1=Deprecated(SUPERSEDED), v2=Published

        val (rolledBack, events) = publishedV2.rollback(SemVer(0, 1, 0), context)

        rolledBack.versions.first { it.semVer == SemVer(0, 1, 0) }.state shouldBe LifecycleState.Published
        rolledBack.versions.first { it.semVer == SemVer(0, 2, 0) }.state shouldBe LifecycleState.Deprecated
        rolledBack.versions.count { it.state == LifecycleState.Published } shouldBe 1

        val rolledBackEvent = events.filterIsInstance<PromptRolledBack>().single()
        rolledBackEvent.payload.fromSemVer shouldBe SemVer(0, 2, 0)
        rolledBackEvent.payload.toSemVer shouldBe SemVer(0, 1, 0)

        val deprecatedEvent = events.filterIsInstance<PromptDeprecated>().single()
        deprecatedEvent.payload.semVer shouldBe SemVer(0, 2, 0)
        deprecatedEvent.payload.reason shouldBe DeprecationReason.SUPERSEDED
        deprecatedEvent.payload.recommendedReplacement shouldBe VersionRef.Fixed(SemVer(0, 1, 0))
    }

    @Test
    fun `rollback は存在しないVersionを指定するとPromptVersionNotFoundExceptionを投げる`() {
        val (published, _) = createPublished()

        shouldThrow<PromptVersionNotFoundException> {
            published.rollback(SemVer(9, 9, 9), context)
        }
    }

    @Test
    fun `rollback は一度もPublishedになったことがないVersionを指定するとInvalidStateTransitionExceptionを投げる`() {
        val (publishedV1, _) = createPublished()
        val (withDraftV2, _) =
            publishedV1.newVersion(
                NewPromptVersion(SemVer(0, 2, 0), PromptContent("Answer(v2): {{question}}")),
                context,
            )
        // v2はDraftのまま一度もPublishedを経ていない

        shouldThrow<InvalidStateTransitionException> {
            withDraftV2.rollback(SemVer(0, 2, 0), context)
        }
    }

    @Test
    fun `rollback はPublished中のVersionが1つも無い場合InvalidStateTransitionExceptionを投げる`() {
        val deprecated = createDeprecated()
        // createDeprecated()は単一Versionのみを持ち、そのVersion自体がDeprecatedへ遷移済みのため
        // 現在Published状態のVersionはPrompt内に1つも存在しない

        shouldThrow<InvalidStateTransitionException> {
            deprecated.rollback(SemVer(0, 1, 0), context)
        }
    }

    @Test
    fun `rollback はArchived状態のVersionを指定するとInvalidStateTransitionExceptionを投げる`() {
        val (publishedV1, _) = createPublished()
        val v2 = approvedSecondVersion(publishedV1)
        val (publishedV2, _) = v2.publish(SemVer(0, 2, 0), allDependenciesPublished = true, context)
        // v1=Deprecated(SUPERSEDED) を、参照ゼロでArchiveまで進める
        val (archived, _) = publishedV2.archive(SemVer(0, 1, 0), referencingClientCount = 0, force = false, context)

        shouldThrow<InvalidStateTransitionException> {
            archived.rollback(SemVer(0, 1, 0), context)
        }
    }

    // ---- Published→Deprecated: deprecate（代替Versionは任意）----

    @Test
    fun `deprecate は代替Versionを指定してPublishedからDeprecatedへ遷移しreason MANUALのPromptDeprecatedを発行する`() {
        val (published, _) = createPublished()

        val (prompt, event) = published.deprecate(SemVer(0, 1, 0), VersionRef.Alias("next"), context)

        prompt.versions.single().state shouldBe LifecycleState.Deprecated
        event.payload.recommendedReplacement shouldBe VersionRef.Alias("next")
        event.payload.reason shouldBe DeprecationReason.MANUAL
    }

    @Test
    fun `deprecate は代替Versionを指定しなくても成功する`() {
        val (published, _) = createPublished()

        val (prompt, event) = published.deprecate(SemVer(0, 1, 0), null, context)

        prompt.versions.single().state shouldBe LifecycleState.Deprecated
        event.payload.recommendedReplacement shouldBe null
    }

    // ---- Deprecated→Archived: archive（ガード: 参照クライアントゼロ確認 or 強制フラグ）----

    @Test
    fun `archive は参照クライアントがゼロの場合Archivedへ遷移しPromptArchivedを発行する`() {
        val deprecated = createDeprecated()

        val (prompt, event) = deprecated.archive(SemVer(0, 1, 0), referencingClientCount = 0, force = false, context)

        prompt.versions.single().state shouldBe LifecycleState.Archived
        event.payload.semVer shouldBe SemVer(0, 1, 0)
    }

    @Test
    fun `archive は強制フラグがtrueなら参照クライアントが残っていてもArchivedへ遷移する`() {
        val deprecated = createDeprecated()

        val (prompt, _) = deprecated.archive(SemVer(0, 1, 0), referencingClientCount = 5, force = true, context)

        prompt.versions.single().state shouldBe LifecycleState.Archived
    }

    @Test
    fun `archive は参照クライアントが残っており強制フラグもfalseの場合InvalidStateTransitionExceptionを投げる`() {
        val deprecated = createDeprecated()

        shouldThrow<InvalidStateTransitionException> {
            deprecated.archive(SemVer(0, 1, 0), referencingClientCount = 5, force = false, context)
        }
    }

    // ---- Draft→Archived: discard（ガードなし）----

    @Test
    fun `discard はDraftからArchivedへ即座に遷移しPromptDiscardedを発行する`() {
        val (created, _) = createDraft()

        val (prompt, event) = created.discard(SemVer(0, 1, 0), context)

        prompt.versions.single().state shouldBe LifecycleState.Archived
        event.eventType shouldBe "PromptDiscarded"
        event.payload.semVer shouldBe SemVer(0, 1, 0)
    }

    @Test
    fun `discard はInReview状態のVersionに対して実行するとInvalidStateTransitionExceptionを投げる`() {
        val inReview = createInReview()

        shouldThrow<InvalidStateTransitionException> {
            inReview.discard(SemVer(0, 1, 0), context)
        }
    }

    // ---- 永続化層からの復元: restore（ADR-0006） ----

    @OptIn(PersistenceApi::class)
    @Test
    fun `restore はMementoのstateをそのまま持つPromptを再構築する`() {
        val memento =
            PromptMemento(
                key,
                listOf(
                    PromptVersionMemento(SemVer(0, 1, 0), content, emptyList(), null, LifecycleState.Deprecated),
                    PromptVersionMemento(SemVer(0, 2, 0), content, emptyList(), null, LifecycleState.Published),
                ),
            )

        val restored = Prompt.restore(memento)

        restored.key shouldBe key
        restored.versions.map { it.semVer to it.state } shouldBe
            listOf(
                SemVer(0, 1, 0) to LifecycleState.Deprecated,
                SemVer(0, 2, 0) to LifecycleState.Published,
            )
    }

    @OptIn(PersistenceApi::class)
    @Test
    fun `restore もPublishedは同時に1Versionまでという不変条件を検証する`() {
        val memento =
            PromptMemento(
                key,
                listOf(
                    PromptVersionMemento(SemVer(0, 1, 0), content, emptyList(), null, LifecycleState.Published),
                    PromptVersionMemento(SemVer(0, 2, 0), content, emptyList(), null, LifecycleState.Published),
                ),
            )

        shouldThrow<IllegalArgumentException> {
            Prompt.restore(memento)
        }
    }

    // ---- テスト用フィクスチャ ----

    private fun createDraft(): Pair<Prompt, PromptCreated> =
        Prompt.create(
            key,
            NewPromptVersion(SemVer(0, 1, 0), content),
            context,
        )

    private fun createInReview(): Prompt {
        val (created, _) = createDraft()
        return created.submitForReview(SemVer(0, 1, 0), validationPassed = true)
    }

    private fun createApproved(): Prompt = createInReview().approve(SemVer(0, 1, 0), 1, 1)

    private fun createPublished(): Pair<Prompt, List<PromptDomainEvent>> =
        createApproved().publish(SemVer(0, 1, 0), allDependenciesPublished = true, context)

    private fun createDeprecated(): Prompt {
        val (published, _) = createPublished()
        val (deprecated, _) = published.deprecate(SemVer(0, 1, 0), VersionRef.Alias("next"), context)
        return deprecated
    }

    /** v1がPublished済みのPromptに対し、v2をDraftからApprovedまで進める。 */
    private fun approvedSecondVersion(publishedV1: Prompt): Prompt {
        val (withDraftV2, _) =
            publishedV1.newVersion(
                NewPromptVersion(SemVer(0, 2, 0), PromptContent("Answer(v2): {{question}}")),
                context,
            )
        val inReviewV2 = withDraftV2.submitForReview(SemVer(0, 2, 0), validationPassed = true)
        return inReviewV2.approve(SemVer(0, 2, 0), approvalCount = 1, requiredApprovalCount = 1)
    }
}
