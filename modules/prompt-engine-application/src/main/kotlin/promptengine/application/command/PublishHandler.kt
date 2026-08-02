package promptengine.application.command

import promptengine.domain.dependency.DependencyEdge
import promptengine.domain.dependency.DependencyKind
import promptengine.domain.dependency.DependencyRepository
import promptengine.domain.event.EventContext
import promptengine.domain.fragment.FragmentKey
import promptengine.domain.fragment.FragmentRepository
import promptengine.domain.prompt.LifecycleState
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.PromptRepository
import promptengine.domain.prompt.PromptVersionNotFoundException
import promptengine.domain.shared.IdempotentCommandExecutor
import promptengine.domain.shared.PublicationState
import promptengine.domain.shared.SemVer
import promptengine.domain.shared.VersionRange
import promptengine.domain.template.TemplateKey
import promptengine.domain.template.TemplateRepository
import java.time.Clock
import java.time.Instant

/** `POST /prompts/{key}/versions/{v}/publish`（設計書§13.1）。 */
data class PublishCommand(
    val key: PromptKey,
    val semVer: SemVer,
    val actor: String,
    val traceId: String,
    val idempotencyKey: String? = null,
) {
    internal fun fingerprintPayload(): String = listOf(key, semVer).toString()
}

data class PublishResult(val key: PromptKey, val semVer: SemVer)

/**
 * ガード「依存先が全てPublished」（設計書§2.5）の評価元:
 * [DependencyRepository.findOutbound]（semVer指定オーバーロード、P9b）で対象Versionが
 * 参照するTemplate/Fragment/Promptを取得し、それぞれの[TemplateRepository]/[FragmentRepository]/
 * [PromptRepository]で該当Versionが公開状態か確認する。P9b時点で書き込まれる依存はextends由来の
 * TEMPLATEのみ（docs/prompts/p9b.md）だが、判定ロジック自体はFRAGMENT/PROMPTにも対応する。
 */
class PublishHandler(
    private val promptRepository: PromptRepository,
    private val templateRepository: TemplateRepository,
    private val fragmentRepository: FragmentRepository,
    private val dependencyRepository: DependencyRepository,
    private val idempotentCommandExecutor: IdempotentCommandExecutor,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun handle(command: PublishCommand): PublishResult =
        idempotentCommandExecutor.executeInTransaction(
            command.idempotencyKey,
            command.fingerprintPayload().sha256(),
            PublishResult::class.java,
        ) {
            val prompt =
                promptRepository.findByKey(command.key)
                    ?: throw PromptVersionNotFoundException.forKey(command.key)
            val edges = dependencyRepository.findOutbound(command.key, command.semVer)
            val allDependenciesPublished = edges.all { isPublished(it) }
            val eventContext =
                EventContext(actor = command.actor, traceId = command.traceId, occurredAt = Instant.now(clock))
            val (updated, events) = prompt.publish(command.semVer, allDependenciesPublished, eventContext)
            val saved = promptRepository.save(updated, events)
            PublishResult(saved.key, command.semVer)
        }

    private fun isPublished(edge: DependencyEdge): Boolean {
        val range = VersionRange.parse(edge.toVersion)
        return when (edge.toKind) {
            DependencyKind.TEMPLATE ->
                templateRepository.findByKey(TemplateKey(edge.toKey))?.versions
                    ?.any { range.matches(it.semVer) && it.state == PublicationState.Published } ?: false
            DependencyKind.FRAGMENT ->
                fragmentRepository.findByKey(FragmentKey(edge.toKey))?.versions
                    ?.any { range.matches(it.semVer) && it.state == PublicationState.Published } ?: false
            DependencyKind.PROMPT ->
                promptRepository.findByKey(PromptKey(edge.toKey))?.versions
                    ?.any { range.matches(it.semVer) && it.state == LifecycleState.Published } ?: false
        }
    }
}
