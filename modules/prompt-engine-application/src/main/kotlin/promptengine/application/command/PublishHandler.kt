package promptengine.application.command

import promptengine.domain.dependency.DependencyRepository
import promptengine.domain.event.EventContext
import promptengine.domain.fragment.FragmentRepository
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.PromptRepository
import promptengine.domain.prompt.PromptVersionNotFoundException
import promptengine.domain.shared.IdempotentCommandExecutor
import promptengine.domain.shared.SemVer
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
    // クラス名を先頭に含める: 他のCommandとfingerprintPayload()の結果が衝突しないため（レビュー指摘）。
    internal fun fingerprintPayload(): String = "${this::class.simpleName}:" + listOf(key, semVer)
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
    templateRepository: TemplateRepository,
    fragmentRepository: FragmentRepository,
    dependencyRepository: DependencyRepository,
    private val idempotentCommandExecutor: IdempotentCommandExecutor,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val dependencyPublicationChecker =
        DependencyPublicationChecker(promptRepository, templateRepository, fragmentRepository, dependencyRepository)

    fun handle(command: PublishCommand): PublishResult =
        idempotentCommandExecutor.executeInTransaction(
            command.idempotencyKey,
            command.fingerprintPayload().sha256(),
            PublishResult::class.java,
        ) {
            val prompt =
                promptRepository.findByKey(command.key)
                    ?: throw PromptVersionNotFoundException.forKey(command.key)
            val version =
                prompt.versions.find { it.semVer == command.semVer }
                    ?: throw PromptVersionNotFoundException(command.semVer)
            val allDependenciesPublished = dependencyPublicationChecker.allDependenciesPublished(command.key, version)
            val eventContext =
                EventContext(actor = command.actor, traceId = command.traceId, occurredAt = Instant.now(clock))
            val (updated, events) = prompt.publish(command.semVer, allDependenciesPublished, eventContext)
            val saved = promptRepository.save(updated, events)
            PublishResult(saved.key, command.semVer)
        }
}
