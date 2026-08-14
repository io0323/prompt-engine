package promptengine.infrastructure.persistence

import promptengine.domain.fragment.FragmentKey
import promptengine.domain.shared.OptimisticLockConflictException

/**
 * 楽観ロック衝突（VERSION_CONFLICT、ADR-0008）。[JdbcFragmentRepository.save] が
 * 期待した `rowVersion` とDB側の現在値が一致しなかった場合に投げる。
 * `ErrorCodeResolver`が判定できるよう[OptimisticLockConflictException]を継承する
 * （ADR-0032決定5）。
 */
class FragmentVersionConflictException(
    val fragmentKey: FragmentKey,
    val expectedRowVersion: Long,
    val actualRowVersion: Long,
) : OptimisticLockConflictException(
        "VERSION_CONFLICT: fragment '${fragmentKey.value}' expected row_version=$expectedRowVersion " +
            "but current row_version=$actualRowVersion",
    )
