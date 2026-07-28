package promptengine.infrastructure.persistence

import promptengine.domain.shared.VersionRange
import promptengine.domain.template.ExtendsRef
import promptengine.domain.template.TemplateKey

/**
 * `extends_key`/`extends_version_range` の2列とdomain型 [ExtendsRef] の相互変換（ADR-0009）。
 * `internal`: `prompt-engine-infrastructure` モジュール内の永続化コードのみが使う。
 */
internal fun ExtendsRef?.toExtendsKeyDbValue(): String? = this?.key?.value

internal fun ExtendsRef?.toExtendsVersionRangeDbValue(): String? = this?.range?.toRangeText()

internal fun extendsRefFromDbValue(
    key: String?,
    versionRange: String?,
): ExtendsRef? = key?.let { ExtendsRef(TemplateKey(it), VersionRange.parse(versionRange)) }
