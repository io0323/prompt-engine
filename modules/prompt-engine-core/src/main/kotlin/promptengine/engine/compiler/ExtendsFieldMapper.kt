package promptengine.engine.compiler

import promptengine.domain.shared.VersionRange
import promptengine.domain.template.ExtendsRef
import promptengine.domain.template.TemplateKey

/**
 * DSLフロントマターの生`extends`文字列（設計書§15.1/§15.3
 * `extends: <templateKey>[@versionRange]`）を[ExtendsRef]へ変換する唯一の経路（ADR-0009）。
 *
 * Template/Prompt Aggregateへ`extends`を設定するコードは、個別に`@`区切りの文字列を
 * 解析するのではなく、必ずこの関数を経由すること（「保存された参照 == DSLソースを
 * パースした結果」という整合性を1箇所に集約するため）。
 */
object ExtendsFieldMapper {
    fun parse(rawExtends: Any?): ExtendsRef? {
        if (rawExtends == null) return null
        require(rawExtends is String) { "extends front matter field must be a string: $rawExtends" }

        val atIndex = rawExtends.indexOf('@')
        val keyText = if (atIndex >= 0) rawExtends.substring(0, atIndex) else rawExtends
        val rangeText = if (atIndex >= 0) rawExtends.substring(atIndex + 1) else null
        return ExtendsRef(TemplateKey(keyText), VersionRange.parse(rangeText))
    }
}
