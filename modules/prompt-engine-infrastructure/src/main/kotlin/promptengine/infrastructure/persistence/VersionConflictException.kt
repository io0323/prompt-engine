package promptengine.infrastructure.persistence

import promptengine.domain.prompt.PromptKey

/**
 * 楽観ロック衝突（VERSION_CONFLICT、設計書§6.3・ADR-0006）。
 * [EventStorePromptRepository.save] が期待した `rowVersion` とDB側の現在値が
 * 一致しなかった場合に投げる。永続化技術に紐づく例外のため
 * `prompt-engine-infrastructure` に置き、domainには追加しない。
 */
class VersionConflictException(
    val promptKey: PromptKey,
    val expectedRowVersion: Long,
    val actualRowVersion: Long,
) : RuntimeException(
        "VERSION_CONFLICT: prompt '${promptKey.value}' expected row_version=$expectedRowVersion " +
            "but current row_version=$actualRowVersion",
    )
