package promptengine.domain.prompt

import promptengine.domain.event.EventContext
import promptengine.domain.shared.SemVer
import java.util.UUID

/**
 * Prompt Authoringコンテキストの Aggregate Root（設計書§2.5・§4.3）。
 *
 * §2.5 のライフサイクル遷移表8件をすべて実装する。ガード条件
 * （Validation合格・承認数・依存先Published・参照ゼロ）はすべて引数として受け取り、
 * Aggregate自身は外部I/Oを一切行わない。
 *
 * 不変条件:
 * - Published は同時に1Version（[init] ブロックで検証。publish/rollbackも自動supersedeで維持する）
 * - Publishedの内容はImmutable（[PromptVersion.withContent] 参照）
 *
 * §2.5 の遷移8件 + create/newVersionをそれぞれ独立したメソッドとして持つため
 * メソッド数がdetektの既定閾値をわずかに超えるが、設計書の遷移表と1:1対応させる
 * ことを優先し、恣意的な分割は行わない。
 */
@Suppress("TooManyFunctions")
data class Prompt(
    val key: PromptKey,
    val versions: List<PromptVersion>,
) {
    init {
        require(versions.isNotEmpty()) { "Prompt must have at least one version" }
        require(versions.count { it.state == LifecycleState.Published } <= 1) {
            "Prompt must not have more than one Published version at the same time"
        }
    }

    private fun version(semVer: SemVer): PromptVersion =
        versions.find {
            it.semVer == semVer
        } ?: throw PromptVersionNotFoundException(semVer)

    private fun replaceState(
        semVer: SemVer,
        newState: LifecycleState,
    ): Prompt = copy(versions = versions.map { if (it.semVer == semVer) it.copy(state = newState) else it })

    companion object {
        /**
         * (新規)→Draft。新規Promptを最初のVersion（Draft状態）とともに作成する。
         * [NewPromptVersion] は `state` を持たないため、Draft以外の状態で
         * 作成しようとすること自体が型として表現できない。
         */
        fun create(
            key: PromptKey,
            version: NewPromptVersion,
            context: EventContext,
        ): Pair<Prompt, PromptCreated> {
            val promptVersion =
                PromptVersion(version.semVer, version.content, version.variables, version.contextRequirement)
            val prompt = Prompt(key, listOf(promptVersion))
            val event =
                PromptCreated(
                    eventId = UUID.randomUUID(),
                    occurredAt = context.occurredAt,
                    aggregateId = key.value,
                    actor = context.actor,
                    traceId = context.traceId,
                    payload = PromptCreated.Payload(key.value, version.semVer),
                )
            return prompt to event
        }
    }

    /**
     * (新規)→Draft。既存Promptに新しいDraft Versionを追加する。
     * [NewPromptVersion] は `state` を持たないため、Draft以外の状態で
     * 作成しようとすること自体が型として表現できない。
     */
    fun newVersion(
        version: NewPromptVersion,
        context: EventContext,
    ): Pair<Prompt, PromptVersionCreated> {
        require(versions.none { it.semVer == version.semVer }) { "version ${version.semVer} already exists" }
        val promptVersion =
            PromptVersion(version.semVer, version.content, version.variables, version.contextRequirement)
        val prompt = copy(versions = versions + promptVersion)
        val event =
            PromptVersionCreated(
                eventId = UUID.randomUUID(),
                occurredAt = context.occurredAt,
                aggregateId = key.value,
                actor = context.actor,
                traceId = context.traceId,
                payload = PromptVersionCreated.Payload(key.value, version.semVer),
            )
        return prompt to event
    }

    /**
     * Draft→InReview。ガードはValidation合格。
     *
     * この遷移に対応するDomain Event（`PromptReviewRequested`、設計書§14）は
     * ReviewCase Aggregateが発行する。ReviewCaseはM2スコープであり、M1の間
     * この遷移は監査ログに記録されない（GitHub Issue #9）。
     */
    fun submitForReview(
        semVer: SemVer,
        validationPassed: Boolean,
    ): Prompt {
        val current = version(semVer)
        if (!validationPassed) {
            throw InvalidStateTransitionException(current.state::class.simpleName ?: "Unknown", "submitForReview")
        }
        return replaceState(semVer, current.state.submitForReview())
    }

    /**
     * InReview→Draft（レビュー担当による差し戻し）。ガードなし。
     *
     * この遷移に対応するDomain Event（`PromptRejected`、設計書§14）は
     * ReviewCase Aggregateが発行する。ReviewCaseはM2スコープであり、M1の間
     * この遷移は監査ログに記録されない（GitHub Issue #9）。
     */
    fun reject(semVer: SemVer): Prompt = replaceState(semVer, version(semVer).state.reject())

    /**
     * InReview→Draft（著者自身によるレビュー取り下げ）。ガードなし。
     * ReviewCaseを経由しないPrompt Aggregate自身の操作のため、[PromptWithdrawn] を発行する（ADR-0004）。
     */
    fun withdraw(
        semVer: SemVer,
        context: EventContext,
    ): Pair<Prompt, PromptWithdrawn> {
        val prompt = replaceState(semVer, version(semVer).state.withdraw())
        val event =
            PromptWithdrawn(
                eventId = UUID.randomUUID(),
                occurredAt = context.occurredAt,
                aggregateId = key.value,
                actor = context.actor,
                traceId = context.traceId,
                payload = PromptWithdrawn.Payload(key.value, semVer),
            )
        return prompt to event
    }

    /**
     * InReview→Approved。ガードは必要承認数充足。
     *
     * この遷移に対応するDomain Event（`PromptApproved`、設計書§14）は
     * ReviewCase Aggregateが発行する。ReviewCaseはM2スコープであり、M1の間
     * この遷移は監査ログに記録されない（GitHub Issue #9）。
     */
    fun approve(
        semVer: SemVer,
        approvalCount: Int,
        requiredApprovalCount: Int,
    ): Prompt {
        val current = version(semVer)
        if (approvalCount < requiredApprovalCount) {
            throw InvalidStateTransitionException(current.state::class.simpleName ?: "Unknown", "approve")
        }
        return replaceState(semVer, current.state.approve())
    }

    /**
     * Approved→Published。ガードは依存先が全てPublished。
     *
     * 他のVersionが現在Publishedであれば、そのVersionを自動的に
     * Deprecated（reason=SUPERSEDED）へ遷移させるアトミック操作とする（ADR-0005）。
     * そのため戻り値のイベントは [PromptPublished] のみ、または
     * [PromptDeprecated]（reason=SUPERSEDED）と [PromptPublished] の2件になる。
     */
    fun publish(
        semVer: SemVer,
        allDependenciesPublished: Boolean,
        context: EventContext,
    ): Pair<Prompt, List<PromptDomainEvent>> {
        val current = version(semVer)
        if (!allDependenciesPublished) {
            throw InvalidStateTransitionException(current.state::class.simpleName ?: "Unknown", "publish")
        }
        val newState = current.state.publish()
        val previouslyPublished = versions.find { it.state == LifecycleState.Published && it.semVer != semVer }

        val events = mutableListOf<PromptDomainEvent>()
        var updatedVersions = versions.map { if (it.semVer == semVer) it.copy(state = newState) else it }

        if (previouslyPublished != null) {
            val supersededState = previouslyPublished.state.deprecate()
            updatedVersions =
                updatedVersions.map {
                    if (it.semVer == previouslyPublished.semVer) it.copy(state = supersededState) else it
                }
            events +=
                PromptDeprecated(
                    eventId = UUID.randomUUID(),
                    occurredAt = context.occurredAt,
                    aggregateId = key.value,
                    actor = context.actor,
                    traceId = context.traceId,
                    payload =
                        PromptDeprecated.Payload(
                            promptKey = key.value,
                            semVer = previouslyPublished.semVer,
                            recommendedReplacement = VersionRef.Fixed(semVer),
                            reason = DeprecationReason.SUPERSEDED,
                        ),
                )
        }

        events +=
            PromptPublished(
                eventId = UUID.randomUUID(),
                occurredAt = context.occurredAt,
                aggregateId = key.value,
                actor = context.actor,
                traceId = context.traceId,
                payload = PromptPublished.Payload(key.value, semVer),
            )

        return copy(versions = updatedVersions) to events
    }

    /**
     * Published→Published（過去Versionの再昇格）。
     *
     * 設計書§2.5 のガードは文字通りには「対象Versionが存在」としか定めていないが、
     * ここでは意図的により厳格に「対象Versionの現在状態がDeprecatedであること」を要求する。
     *
     * 理由:
     * - rollbackの実運用は「誤ってdeprecateしたVersionの復帰」（§2.13、ADR-0005）であり、
     *   DraftやInReviewなど一度もPublishedを経ていないVersionをrollbackで直接Publishedに
     *   できてしまうと、submitForReview/approveの承認フローをバイパスして未承認の内容を
     *   配信できてしまう。
     * - Archivedは「参照クライアントゼロ確認 or 強制フラグ」（§2.5）を経て意図的にretire
     *   されたVersionであり、rollbackで暗黙に復活できてしまうと、そのretire判断を
     *   無効化してしまう。Archivedへ戻したい場合はrollbackの対象外とする。
     *
     * publishと同様、rollback時点のPublished VersionをDeprecated（reason=SUPERSEDED）へ
     * 自動的に遷移させるアトミック操作とする（ADR-0005）。
     */
    fun rollback(
        targetSemVer: SemVer,
        context: EventContext,
    ): Pair<Prompt, List<PromptDomainEvent>> {
        val target = version(targetSemVer)
        if (target.state != LifecycleState.Deprecated) {
            throw InvalidStateTransitionException(target.state::class.simpleName ?: "Unknown", "rollback")
        }
        val currentlyPublished =
            versions.find { it.state == LifecycleState.Published }
                ?: throw InvalidStateTransitionException("NoPublishedVersion", "rollback")
        currentlyPublished.state.rollback()

        val updatedVersions =
            versions.map {
                when (it.semVer) {
                    targetSemVer -> it.copy(state = LifecycleState.Published)
                    currentlyPublished.semVer -> it.copy(state = it.state.deprecate())
                    else -> it
                }
            }

        val events =
            listOf(
                PromptDeprecated(
                    eventId = UUID.randomUUID(),
                    occurredAt = context.occurredAt,
                    aggregateId = key.value,
                    actor = context.actor,
                    traceId = context.traceId,
                    payload =
                        PromptDeprecated.Payload(
                            promptKey = key.value,
                            semVer = currentlyPublished.semVer,
                            recommendedReplacement = VersionRef.Fixed(targetSemVer),
                            reason = DeprecationReason.SUPERSEDED,
                        ),
                ),
                PromptRolledBack(
                    eventId = UUID.randomUUID(),
                    occurredAt = context.occurredAt,
                    aggregateId = key.value,
                    actor = context.actor,
                    traceId = context.traceId,
                    payload = PromptRolledBack.Payload(key.value, currentlyPublished.semVer, targetSemVer),
                ),
            )

        return copy(versions = updatedVersions) to events
    }

    /** Published→Deprecated（手動）。代替Version（recommendedReplacement）は任意。 */
    fun deprecate(
        semVer: SemVer,
        recommendedReplacement: VersionRef?,
        context: EventContext,
    ): Pair<Prompt, PromptDeprecated> {
        val newState = version(semVer).state.deprecate()
        val prompt = replaceState(semVer, newState)
        val event =
            PromptDeprecated(
                eventId = UUID.randomUUID(),
                occurredAt = context.occurredAt,
                aggregateId = key.value,
                actor = context.actor,
                traceId = context.traceId,
                payload =
                    PromptDeprecated.Payload(
                        promptKey = key.value,
                        semVer = semVer,
                        recommendedReplacement = recommendedReplacement,
                        reason = DeprecationReason.MANUAL,
                    ),
            )
        return prompt to event
    }

    /** Deprecated→Archived。ガードは参照クライアントゼロ確認 or 強制フラグ。 */
    fun archive(
        semVer: SemVer,
        referencingClientCount: Int,
        force: Boolean,
        context: EventContext,
    ): Pair<Prompt, PromptArchived> {
        val current = version(semVer)
        if (referencingClientCount > 0 && !force) {
            throw InvalidStateTransitionException(current.state::class.simpleName ?: "Unknown", "archive")
        }
        val prompt = replaceState(semVer, current.state.archive())
        val event =
            PromptArchived(
                eventId = UUID.randomUUID(),
                occurredAt = context.occurredAt,
                aggregateId = key.value,
                actor = context.actor,
                traceId = context.traceId,
                payload = PromptArchived.Payload(key.value, semVer),
            )
        return prompt to event
    }

    /** Draft→Archived（Draftの破棄）。ガードなし。 */
    fun discard(
        semVer: SemVer,
        context: EventContext,
    ): Pair<Prompt, PromptDiscarded> {
        val prompt = replaceState(semVer, version(semVer).state.discard())
        val event =
            PromptDiscarded(
                eventId = UUID.randomUUID(),
                occurredAt = context.occurredAt,
                aggregateId = key.value,
                actor = context.actor,
                traceId = context.traceId,
                payload = PromptDiscarded.Payload(key.value, semVer),
            )
        return prompt to event
    }
}
