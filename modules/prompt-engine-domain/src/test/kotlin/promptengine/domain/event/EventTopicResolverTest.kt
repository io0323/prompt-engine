package promptengine.domain.event

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** [EventTopicResolver]の単体テスト（設計書§14の表、ADR-0025決定7）。 */
class EventTopicResolverTest {
    private val eventTypeToTopic: Map<String, EventTopic> =
        mapOf(
            "PromptCreated" to EventTopic.PE_PROMPT,
            "PromptVersionCreated" to EventTopic.PE_PROMPT,
            "PromptUpdated" to EventTopic.PE_PROMPT,
            "PromptPublished" to EventTopic.PE_PROMPT,
            "PromptRolledBack" to EventTopic.PE_PROMPT,
            "PromptDeprecated" to EventTopic.PE_PROMPT,
            "PromptArchived" to EventTopic.PE_PROMPT,
            "PromptDiscarded" to EventTopic.PE_PROMPT,
            "PromptCompiled" to EventTopic.PE_PROMPT,
            "PromptValidated" to EventTopic.PE_PROMPT,
            "PromptValidationFailed" to EventTopic.PE_PROMPT,
            "PromptOptimized" to EventTopic.PE_PROMPT,
            "PromptRendered" to EventTopic.PE_PROMPT,
            "CacheInvalidated" to EventTopic.PE_PROMPT,
            "TemplateCreated" to EventTopic.PE_PROMPT,
            "TemplateVersionCreated" to EventTopic.PE_PROMPT,
            "TemplatePublished" to EventTopic.PE_PROMPT,
            "TemplateArchived" to EventTopic.PE_PROMPT,
            "FragmentCreated" to EventTopic.PE_PROMPT,
            "FragmentVersionCreated" to EventTopic.PE_PROMPT,
            "FragmentPublished" to EventTopic.PE_PROMPT,
            "FragmentArchived" to EventTopic.PE_PROMPT,
            "PromptExecuted" to EventTopic.PE_EXECUTION,
            "PromptExecutionFailed" to EventTopic.PE_EXECUTION,
            "ResponseParsed" to EventTopic.PE_EXECUTION,
            "ResponseParseFailed" to EventTopic.PE_EXECUTION,
            "PromptEvaluationCompleted" to EventTopic.PE_EVALUATION,
            "ExperimentStarted" to EventTopic.PE_EXPERIMENT,
            "ExperimentStopped" to EventTopic.PE_EXPERIMENT,
            "ExperimentWinnerDeclared" to EventTopic.PE_EXPERIMENT,
            "ExperimentCompleted" to EventTopic.PE_EXPERIMENT,
            "PromptReviewRequested" to EventTopic.PE_GOVERNANCE,
            "PromptWithdrawn" to EventTopic.PE_GOVERNANCE,
            "PromptApproved" to EventTopic.PE_GOVERNANCE,
            "PromptRejected" to EventTopic.PE_GOVERNANCE,
            "PluginRegistered" to EventTopic.PE_PLUGIN,
            "PluginActivated" to EventTopic.PE_PLUGIN,
            "PluginFailed" to EventTopic.PE_PLUGIN,
        )

    @Test
    fun `設計書14の全イベント名が対応するTopicへ解決される`() {
        eventTypeToTopic.forEach { (eventType, expectedTopic) ->
            EventTopicResolver.resolve(eventType) shouldBe expectedTopic
        }
    }

    @Test
    fun `全6トピックがいずれか1件以上のイベントに割り当てられている`() {
        eventTypeToTopic.values.toSet() shouldBe EventTopic.entries.toSet()
    }

    @Test
    fun `未知のeventTypeはIllegalArgumentExceptionを投げる`() {
        val exception =
            shouldThrow<IllegalArgumentException> {
                EventTopicResolver.resolve("SomethingNotInTheDesignDoc")
            }

        exception.message shouldBe
            "Unknown eventType for topic routing: 'SomethingNotInTheDesignDoc'. " +
            "Only event names listed in 設計書§14 are routable (ADR-0025)."
    }
}
