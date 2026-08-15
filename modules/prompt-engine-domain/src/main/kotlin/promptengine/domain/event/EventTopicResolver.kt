package promptengine.domain.event

/**
 * [DomainEvent.eventType]から配信先[EventTopic]を解決する（設計書§14の表、ADR-0025決定7）。
 *
 * 設計書§14に列挙された38件のイベント名のみを扱う閉じた集合として実装する
 * （M2-3でTemplate/Fragmentの8件を追加、ADR-0033）。
 * 未知の`eventType`は設計書に存在しないイベントであるため、フォールバックせず
 * [IllegalArgumentException]で失敗させる（CLAUDE.md「設計書にない...イベントを
 * 勝手に追加しない」の裏面。中継実装側で未定義イベントを静かに握りつぶさない）。
 */
object EventTopicResolver {
    /** 設計書§14の表（イベント名→Topic）。 */
    private val topicByEventType: Map<String, EventTopic> =
        buildMap {
            putAllFor(
                EventTopic.PE_PROMPT,
                "PromptCreated",
                "PromptVersionCreated",
                "PromptUpdated",
                "PromptPublished",
                "PromptRolledBack",
                "PromptDeprecated",
                "PromptArchived",
                "PromptDiscarded",
                "PromptCompiled",
                "PromptValidated",
                "PromptValidationFailed",
                "PromptOptimized",
                "PromptRendered",
                "CacheInvalidated",
                "TemplateCreated",
                "TemplateVersionCreated",
                "TemplatePublished",
                "TemplateArchived",
                "FragmentCreated",
                "FragmentVersionCreated",
                "FragmentPublished",
                "FragmentArchived",
            )
            putAllFor(
                EventTopic.PE_EXECUTION,
                "PromptExecuted",
                "PromptExecutionFailed",
                "ResponseParsed",
                "ResponseParseFailed",
            )
            putAllFor(EventTopic.PE_EVALUATION, "PromptEvaluationCompleted")
            putAllFor(
                EventTopic.PE_EXPERIMENT,
                "ExperimentStarted",
                "ExperimentStopped",
                "ExperimentWinnerDeclared",
                "ExperimentCompleted",
            )
            putAllFor(
                EventTopic.PE_GOVERNANCE,
                "PromptReviewRequested",
                "PromptWithdrawn",
                "PromptApproved",
                "PromptRejected",
            )
            putAllFor(EventTopic.PE_PLUGIN, "PluginRegistered", "PluginActivated", "PluginFailed")
        }

    private fun MutableMap<String, EventTopic>.putAllFor(
        topic: EventTopic,
        vararg eventTypes: String,
    ) {
        eventTypes.forEach { put(it, topic) }
    }

    /** [eventType]に対応する[EventTopic]を返す。未知の場合は[IllegalArgumentException]。 */
    fun resolve(eventType: String): EventTopic =
        topicByEventType[eventType]
            ?: throw IllegalArgumentException(
                "Unknown eventType for topic routing: '$eventType'. " +
                    "Only event names listed in 設計書§14 are routable (ADR-0025).",
            )
}
