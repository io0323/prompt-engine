package promptengine.infrastructure.subscriber

import promptengine.domain.cache.PromptCache
import promptengine.domain.dependency.DependencyRepository
import promptengine.domain.event.EventEnvelope
import promptengine.domain.event.EventSubscriber
import promptengine.domain.event.EventTopic
import promptengine.domain.shared.VersionRange

/**
 * Promptの配信内容が変化するイベントを購読し、そのPromptのキャッシュを無効化する
 * （設計書§14 `CacheInvalidated`、ADR-0026決定6、Template/Fragment対応はADR-0033）。
 *
 * `PromptPublished`（配信Version切替）を主対象とし、配信内容が実質的に切り替わる
 * `PromptRolledBack` / `PromptArchived` / `PromptDiscarded`も同じ扱いにする。
 * M2-3で`TemplatePublished`/`TemplateArchived`/`FragmentPublished`/`FragmentArchived`を
 * 追加した: これらは対象Template/Fragmentへの逆依存（[DependencyRepository]）のうち、
 * `toVersion`（SemVer範囲）が発行された`semVer`にマッチするPromptだけを無効化する
 * （多段階のグラフ探索は行わない。理由は[DependencyRepository.findInboundTemplateOrFragment]
 * のKDoc・ADR-0033決定3参照）。
 *
 * キーの復元は[CacheInvalidationPayloadCodec]（`payload`ベース、ADR-0033）に委ねる。
 */
class CacheInvalidationSubscriber(
    private val promptCache: PromptCache,
    private val dependencyRepository: DependencyRepository,
    private val payloadCodec: CacheInvalidationPayloadCodec,
) : EventSubscriber {
    override val name: String = SUBSCRIBER_NAME

    override val topics: Set<EventTopic> = setOf(EventTopic.PE_PROMPT)

    override fun handle(envelope: EventEnvelope) {
        if (envelope.eventType !in INVALIDATING_EVENT_TYPES) return
        when (val target = payloadCodec.decode(envelope) ?: return) {
            is CacheInvalidationTarget.DirectPrompt -> promptCache.invalidateByPrompt(target.promptKey)
            is CacheInvalidationTarget.TemplateOrFragmentVersionChanged -> invalidateDependents(target)
        }
    }

    private fun invalidateDependents(target: CacheInvalidationTarget.TemplateOrFragmentVersionChanged) {
        dependencyRepository
            .findInboundTemplateOrFragment(target.kind, target.key)
            .asSequence()
            .filter { VersionRange.parse(it.toVersion).matches(target.semVer) }
            .map { it.fromKey }
            .distinct()
            .forEach { promptCache.invalidateByPrompt(it) }
    }

    companion object {
        /** Brokerのconsumer group IDおよび`dead_letter_queue.subscriber_name`。 */
        const val SUBSCRIBER_NAME = "pe-cache-invalidator"

        /** 配信されるPrompt/Template/Fragmentの内容が実質的に切り替わる、設計書§14の`pe.prompt`イベント。 */
        val INVALIDATING_EVENT_TYPES =
            setOf(
                "PromptPublished",
                "PromptRolledBack",
                "PromptArchived",
                "PromptDiscarded",
                "TemplatePublished",
                "TemplateArchived",
                "FragmentPublished",
                "FragmentArchived",
            )
    }
}
