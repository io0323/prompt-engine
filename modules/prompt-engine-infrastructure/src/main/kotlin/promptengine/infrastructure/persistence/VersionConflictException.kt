package promptengine.infrastructure.persistence

import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.OptimisticLockConflictException

/**
 * 楽観ロック衝突（VERSION_CONFLICT、設計書§6.3・ADR-0006）。
 * [EventStorePromptRepository.save] が期待した `rowVersion` とDB側の現在値が
 * 一致しなかった場合に投げる。`rowVersion`等の永続化技術に紐づく詳細を持つ具象クラス
 * のため`prompt-engine-infrastructure`に置くが、`ErrorCodeResolver`が判定できるよう
 * 抽象型[OptimisticLockConflictException]を継承する（ADR-0032決定5）。
 */
class VersionConflictException(
    val promptKey: PromptKey,
    val expectedRowVersion: Long,
    val actualRowVersion: Long,
) : OptimisticLockConflictException(
        "VERSION_CONFLICT: prompt '${promptKey.value}' expected row_version=$expectedRowVersion " +
            "but current row_version=$actualRowVersion",
    )
