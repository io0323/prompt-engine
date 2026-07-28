package promptengine.engine.compiler

import promptengine.domain.composition.DuplicateImportAliasException
import promptengine.domain.fragment.FragmentKey
import promptengine.domain.shared.VersionRange

/** `imports:` フロントマターの1エントリ（設計書§15.4）。DB永続化対象ではない一時的な値型（ADR-0010決定7）。 */
data class ImportDeclaration(
    val alias: String,
    val fragmentKey: FragmentKey,
    val range: VersionRange,
)

/**
 * DSLフロントマターの生`imports`フィールド（設計書§15.4
 * `imports: [{alias: safety, ref: fragments/safety-policy@^2}]`）を[ImportDeclaration]の
 * リストへ変換する唯一の経路（[ExtendsFieldMapper]と同型のパターン、ADR-0010決定7）。
 */
object ImportsFieldMapper {
    fun parse(rawImports: Any?): List<ImportDeclaration> {
        if (rawImports == null) return emptyList()
        require(rawImports is List<*>) { "imports front matter field must be a list: $rawImports" }

        val declarations = rawImports.map(::parseEntry)
        val duplicateAlias = declarations.groupingBy { it.alias }.eachCount().entries.firstOrNull { it.value > 1 }
        if (duplicateAlias != null) throw DuplicateImportAliasException(duplicateAlias.key)
        return declarations
    }

    private fun parseEntry(raw: Any?): ImportDeclaration {
        require(raw is Map<*, *>) { "each imports entry must be a mapping: $raw" }
        val alias = raw["alias"] as? String
        val ref = raw["ref"] as? String
        require(!alias.isNullOrBlank()) { "imports entry missing 'alias': $raw" }
        require(!ref.isNullOrBlank()) { "imports entry missing 'ref': $raw" }

        val atIndex = ref.indexOf('@')
        val keyText = if (atIndex >= 0) ref.substring(0, atIndex) else ref
        val rangeText = if (atIndex >= 0) ref.substring(atIndex + 1) else null
        return ImportDeclaration(alias, FragmentKey(keyText), VersionRange.parse(rangeText))
    }
}
