package promptengine.infrastructure.persistence

import promptengine.domain.template.TemplateKey

/**
 * 楽観ロック衝突（VERSION_CONFLICT、ADR-0008）。[JdbcTemplateRepository.save] が
 * 期待した `rowVersion` とDB側の現在値が一致しなかった場合に投げる。
 */
class TemplateVersionConflictException(
    val templateKey: TemplateKey,
    val expectedRowVersion: Long,
    val actualRowVersion: Long,
) : RuntimeException(
        "VERSION_CONFLICT: template '${templateKey.value}' expected row_version=$expectedRowVersion " +
            "but current row_version=$actualRowVersion",
    )
