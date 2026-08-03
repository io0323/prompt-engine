package promptengine.application.command

import promptengine.domain.prompt.PromptAlias
import promptengine.domain.prompt.PromptAliasRepository
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.PromptRepository
import promptengine.domain.prompt.PromptVersionNotFoundException
import promptengine.domain.shared.IdempotentCommandExecutor
import promptengine.domain.shared.SemVer

/**
 * `POST /prompts/{key}/aliases`（設計書§13.1、`{alias, version}`）。§14にAlias専用の
 * Domain Eventは定義されていないため発行しない。
 */
data class SetAliasCommand(
    val key: PromptKey,
    val alias: String,
    val semVer: SemVer,
    val idempotencyKey: String? = null,
) {
    // クラス名を先頭に含める: 他のCommandとfingerprintPayload()の結果が衝突しないため（レビュー指摘）。
    internal fun fingerprintPayload(): String = "${this::class.simpleName}:" + listOf(key, alias, semVer)
}

data class SetAliasResult(val key: PromptKey, val alias: String, val semVer: SemVer)

class SetAliasHandler(
    private val promptRepository: PromptRepository,
    private val promptAliasRepository: PromptAliasRepository,
    private val idempotentCommandExecutor: IdempotentCommandExecutor,
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
            SetAliasResult(command.key, command.alias, command.semVer)
        }
}
