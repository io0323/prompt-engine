package promptengine.domain.template

import promptengine.domain.shared.VersionRange

/**
 * extends参照（設計書§15.1/§15.3 `extends: <templateKey>[@versionRange]`）。
 * TemplateVersion（Template extends Template）とPromptVersion（Prompt extends Template）の
 * どちらも、参照先は常に [TemplateKey]（ADR-0009）。
 */
data class ExtendsRef(
    val key: TemplateKey,
    val range: VersionRange = VersionRange.Latest,
)
