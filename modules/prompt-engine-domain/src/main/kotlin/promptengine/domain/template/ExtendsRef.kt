package promptengine.domain.template

import promptengine.domain.shared.ExtendsRefApi
import promptengine.domain.shared.VersionRange

/**
 * extends参照（設計書§15.1/§15.3 `extends: <templateKey>[@versionRange]`）。
 * TemplateVersion（Template extends Template）とPromptVersion（Prompt extends Template）の
 * どちらも、参照先は常に [TemplateKey]（ADR-0009）。
 *
 * 直接構築（プライマリコンストラクタ・`copy()`）は[ExtendsRefApi]でゲートする。
 * DSLソースの`content.source`と無関係な値を任意に作れてしまうと、
 * 「保存された参照 == DSLソースをパースした結果」という整合性が型で保証できなくなるため
 * （ADR-0009）。
 */
data class ExtendsRef
    @ExtendsRefApi
    constructor(
        val key: TemplateKey,
        val range: VersionRange = VersionRange.Latest,
    )
