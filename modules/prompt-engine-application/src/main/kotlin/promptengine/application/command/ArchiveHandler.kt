package promptengine.application.command

import promptengine.domain.dependency.DependencyRepository
import promptengine.domain.event.EventContext
import promptengine.domain.prompt.ArchiveEligibility
import promptengine.domain.prompt.ArchiveEligibilityRepository
import promptengine.domain.prompt.ArchiveGuardSettings
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
 * P10bで`execution_logs`への書き込み経路（`ExecutionLogSubscriber`）が入ったため、
 * 「直近[ArchiveGuardSettings.inactivityThreshold]の間に実行が無いこと」をガードに使えるようになった
 * （[Issue #48](https://github.com/io0323/prompt-engine/issues/48)、ADR-0026決定5）。
 *
 * ## 既知の限界: カットオーバー以前のPromptは恒久的にforce専用
 * `execution_logs`はP10b以降にしか行が入らないため、「実行記録が無い」は
 * 「一度も実行されていない（＝参照ゼロ、archiveしてよい）」と
 * 「実行されたが記録が残っていない（＝判断不能）」の2つを区別できない。
 * このため[ArchiveGuardSettings.executionLogsCutoverAt]（`execution_logs`が信頼できるようになった時刻）を設定し、
 * `prompt_versions.created_at`がそれ以前のVersionは[ArchiveEligibility.PreCutover]として
 * 判断不能扱いにし、従来通り`force=true`を必須にする。
 *
 * **これはP10b以前に作られたPromptが恒久的にforce専用のまま残ることを意味する。**
 * 意図的に受け入れたトレードオフであり不具合ではない（ADR-0026決定5）。
 * 将来これを解消するには、別の参照追跡手段（AACP側のクライアント登録等）を導入するか、
 * 運用でカットオーバーを引き直す必要がある。
 *
 * [DependencyRepository.findInbound]の件数は参考情報として[ArchiveResult]に含めるが、
 * ガード判定には使わない（「近そうな値で埋めない」というレビュー方針を継続）。
 */
data class ArchiveCommand(
    val key: PromptKey,
    val semVer: SemVer,
    val force: Boolean,
    val actor: String,
    val traceId: String,
    val idempotencyKey: String? = null,
) {
    // クラス名を先頭に含める: 他のCommandとfingerprintPayload()の結果が衝突しないため（レビュー指摘）。
    internal fun fingerprintPayload(): String = "${this::class.simpleName}:" + listOf(key, semVer, force)
}

data class ArchiveResult(val key: PromptKey, val semVer: SemVer, val structuralInboundDependencyCount: Int)

class ArchiveHandler(
    private val promptRepository: PromptRepository,
    private val dependencyRepository: DependencyRepository,
    private val idempotentCommandExecutor: IdempotentCommandExecutor,
    private val archiveEligibilityRepository: ArchiveEligibilityRepository,
    private val guardSettings: ArchiveGuardSettings,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun handle(command: ArchiveCommand): ArchiveResult =
        idempotentCommandExecutor.executeInTransaction(
            command.idempotencyKey,
            command.fingerprintPayload().sha256(),
            ArchiveResult::class.java,
        ) {
            val now = Instant.now(clock)
            if (!command.force) {
                requireInactive(command, now)
            }
            val prompt =
                promptRepository.findByKey(command.key)
                    ?: throw PromptVersionNotFoundException.forKey(command.key)
            val inboundCount = dependencyRepository.findInbound(command.key).size
            val eventContext = EventContext(actor = command.actor, traceId = command.traceId, occurredAt = now)
            // referencingClientCount=0固定でAggregateへ渡す: 参照ゼロの確認（force=false経路では
            // requireInactive、force=true経路では呼出元の明示的な意思）は本ハンドラが既に
            // 済ませており、Prompt.archiveのガード式は`referencingClientCount > 0 && !force`
            // なので、force=trueを渡すこの経路ではreferencingClientCountの値は判定に影響しない。
            val (updated, event) =
                prompt.archive(command.semVer, referencingClientCount = 0, force = true, eventContext)
            val saved = promptRepository.save(updated, listOf(event))
            ArchiveResult(saved.key, command.semVer, inboundCount)
        }

    /**
     * `force=false`の場合のみ呼ばれる。[ArchiveEligibility.Inactive]以外はすべてarchiveを拒否する
     * （判断できない[ArchiveEligibility.PreCutover]も拒否側に倒す。ガードの目的は
     * 「参照されているものを誤って落とさない」ことであり、判断不能を許可側へ倒すと
     * その目的を果たさないため）。
     */
    private fun requireInactive(
        command: ArchiveCommand,
        now: Instant,
    ) {
        val eligibility =
            archiveEligibilityRepository.evaluate(
                command.key,
                command.semVer,
                guardSettings.executionLogsCutoverAt,
                now.minus(guardSettings.inactivityThreshold),
            )
        when (eligibility) {
            ArchiveEligibility.Inactive -> Unit
            ArchiveEligibility.VersionNotFound -> throw PromptVersionNotFoundException.forKey(command.key)
            ArchiveEligibility.PreCutover,
            ArchiveEligibility.RecentlyExecuted,
            -> throw ArchiveRequiresForceException(command.key, command.semVer)
        }
    }
}
