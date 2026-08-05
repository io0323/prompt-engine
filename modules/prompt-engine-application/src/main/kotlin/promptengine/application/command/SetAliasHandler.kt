package promptengine.application.command

import promptengine.domain.audit.AuditLogEntry
import promptengine.domain.audit.AuditRepository
import promptengine.domain.prompt.PromptAlias
import promptengine.domain.prompt.PromptAliasRepository
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.PromptRepository
import promptengine.domain.prompt.PromptVersionNotFoundException
import promptengine.domain.shared.IdempotentCommandExecutor
import promptengine.domain.shared.SemVer
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * `POST /prompts/{key}/aliases`（設計書§13.1、`{alias, version}`）。§14にAlias専用の
 * Domain Eventは定義されていないため発行しない。
 *
 * [actor]/[traceId]は変更の帰属を残すため（CodeRabbitレビュー指摘: 以前はAliasControllerが
 * これらを渡していなかった）、[AuditRepository.record]（CRUD/lifecycle系ハンドラ向けの
 * 一般形、ADR-0017）へ記録する。専用のDomain Eventを新設しない代わりにこの経路を使う。
 */
data class SetAliasCommand(
    val key: PromptKey,
    val alias: String,
    val semVer: SemVer,
    val actor: String,
    val traceId: String,
    val idempotencyKey: String? = null,
) {
    // クラス名を先頭に含める: 他のCommandとfingerprintPayload()の結果が衝突しないため（レビュー指摘）。
    internal fun fingerprintPayload(): String = "${this::class.simpleName}:" + listOf(key, alias, semVer)
}

data class SetAliasResult(val key: PromptKey, val alias: String, val semVer: SemVer)

class SetAliasHandler(
    private val promptRepository: PromptRepository,
    private val promptAliasRepository: PromptAliasRepository,
    private val auditRepository: AuditRepository,
    private val idempotentCommandExecutor: IdempotentCommandExecutor,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun handle(command: SetAliasCommand): SetAliasResult =
        idempotentCommandExecutor.executeInTransaction(
            command.idempotencyKey,
            command.fingerprintPayload().sha256(),
            SetAliasResult::class.java,
        ) {
            val prompt =
                promptRepository.findByKey(command.key)
                    ?: throw PromptVersionNotFoundException.forKey(command.key)
            if (prompt.versions.none { it.semVer == command.semVer }) {
                throw PromptVersionNotFoundException(command.semVer)
            }
            promptAliasRepository.upsert(PromptAlias(command.key, command.alias, command.semVer))
            auditRepository.record(
                AuditLogEntry(
                    auditId = UUID.randomUUID(),
                    aggregateType = "Prompt",
                    aggregateId = command.key.value,
                    action = "AliasSet",
                    actor = command.actor,
                    payload = """{"alias":"${command.alias}","semVer":"${command.semVer}"}""",
                    traceId = command.traceId,
                    occurredAt = Instant.now(clock),
                ),
            )
            SetAliasResult(command.key, command.alias, command.semVer)
        }
}
