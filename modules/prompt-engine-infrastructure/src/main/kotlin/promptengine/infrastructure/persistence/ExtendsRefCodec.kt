package promptengine.infrastructure.persistence

import promptengine.domain.shared.ExtendsRefApi
import promptengine.domain.shared.VersionRange
import promptengine.domain.template.ExtendsRef
import promptengine.domain.template.TemplateKey

/**
 * `extends_key`/`extends_version_range` の2列とdomain型 [ExtendsRef] の相互変換（ADR-0009）。
 * `internal`: `prompt-engine-infrastructure` モジュール内の永続化コードのみが使う。
 *
 * [extendsRefFromDbValue] はDBの行（過去に[promptengine.engine.compiler.ExtendsFieldMapper]
 * 経由で書き込まれた結果であることを信頼する復元経路）からの構築であり、[ExtendsRefApi]で
 * 許可された2箇所目の直接構築経路。
 */
internal fun ExtendsRef?.toExtendsKeyDbValue(): String? = this?.key?.value

internal fun ExtendsRef?.toExtendsVersionRangeDbValue(): String? = this?.range?.toRangeText()

@OptIn(ExtendsRefApi::class)
internal fun extendsRefFromDbValue(
    key: String?,
    versionRange: String?,
): ExtendsRef? = key?.let { ExtendsRef(TemplateKey(it), VersionRange.parse(versionRange)) }
