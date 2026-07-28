package promptengine.infrastructure.persistence

import promptengine.domain.fragment.FragmentKey

/**
 * 楽観ロック衝突（VERSION_CONFLICT、ADR-0008）。[JdbcFragmentRepository.save] が
 * 期待した `rowVersion` とDB側の現在値が一致しなかった場合に投げる。
 */
class FragmentVersionConflictException(
    val fragmentKey: FragmentKey,
    val expectedRowVersion: Long,
    val actualRowVersion: Long,
) : RuntimeException(
        "VERSION_CONFLICT: fragment '${fragmentKey.value}' expected row_version=$expectedRowVersion " +
            "but current row_version=$actualRowVersion",
    )
