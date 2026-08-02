package promptengine.application.command

import promptengine.domain.dependency.DependencyRepository
import promptengine.domain.event.EventContext
import promptengine.domain.prompt.ArchiveRequiresForceException
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.PromptRepository
import promptengine.domain.prompt.PromptVersionNotFoundException
import promptengine.domain.shared.IdempotentCommandExecutor
import promptengine.domain.shared.SemVer
import java.time.Clock
import java.time.Instant

/**
 * `DELETE /prompts/{key}`（設計書§13.1、`Deprecated→Archived`）。
 *
 * ガード「参照クライアントゼロ確認 or 強制フラグ」（設計書§2.5）の評価元:
 * `execution_logs`（設計書§12）への書き込み経路が現状無いため（[Issue #48]
 * (https://github.com/io0323/prompt-engine/issues/48)）、M1では真の参照クライアント数を
 * 評価しない。[force]が`false`の場合は[ArchiveRequiresForceException]を投げ、`true`のみ
 * 受け付ける。[DependencyRepository.findInbound]の件数は参考情報として[ArchiveResult]に
 * 含めるが、ガード判定には使わない（「近そうな値で埋めない」というレビュー方針）。
 */
data class ArchiveCommand(
    val key: PromptKey,
    val semVer: SemVer,
    val force: Boolean,
    val actor: String,
    val traceId: String,
    val idempotencyKey: String? = null,
) {
    internal fun fingerprintPayload(): String = listOf(key, semVer, force).toString()
}

data class ArchiveResult(val key: PromptKey, val semVer: SemVer, val structuralInboundDependencyCount: Int)

class ArchiveHandler(
    private val promptRepository: PromptRepository,
    private val dependencyRepository: DependencyRepository,
    private val idempotentCommandExecutor: IdempotentCommandExecutor,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun handle(command: ArchiveCommand): ArchiveResult =
        idempotentCommandExecutor.executeInTransaction(
            command.idempotencyKey,
            command.fingerprintPayload().sha256(),
            ArchiveResult::class.java,
        ) {
            if (!command.force) {
                throw ArchiveRequiresForceException(command.key, command.semVer)
            }
            val prompt =
                promptRepository.findByKey(command.key)
                    ?: throw PromptVersionNotFoundException.forKey(command.key)
            val inboundCount = dependencyRepository.findInbound(command.key).size
            val eventContext =
                EventContext(actor = command.actor, traceId = command.traceId, occurredAt = Instant.now(clock))
            // referencingClientCount=0固定でAggregateへ渡す: force=trueであることを本ハンドラが
            // 既に確認済みであり、Prompt.archiveのガード式は`referencingClientCount > 0 && !force`
            // なので、force=trueの経路ではreferencingClientCountの値そのものは判定に影響しない。
            val (updated, event) =
                prompt.archive(command.semVer, referencingClientCount = 0, force = true, eventContext)
            val saved = promptRepository.save(updated, listOf(event))
            ArchiveResult(saved.key, command.semVer, inboundCount)
        }
}
