package promptengine.engine.compiler

import promptengine.domain.composition.NestedPromptNotSupportedException
import promptengine.domain.fragment.FragmentKey
import promptengine.domain.shared.VersionRange

/** Include先として解決されたFragment参照（key + 解決前のVersion範囲）。 */
data class FragmentReference(val fragmentKey: FragmentKey, val range: VersionRange)

/**
 * `{{> <alias|fragmentKey>[@versionRange] }}`（設計書§15.5）の`target`を、
 * `imports:`宣言（[ImportDeclaration]）を踏まえて[FragmentReference]へ解決する
 * （ADR-0010決定7）。実際のFragment取得・Status検証は行わない（PR2bのスコープ）。
 */
object IncludeTargetResolver {
    private const val NESTED_PROMPT_PREFIX = "prompt:"

    /**
     * [target]が`"prompt:"`で始まる場合は[NestedPromptNotSupportedException]を投げる
     * （ADR-0009決定3）。[target]が[imports]の宣言済みaliasと一致する場合はそのFragmentKeyを、
     * 一致しない場合は[target]自体をFragmentKeyの生の値として使う。Version範囲は
     * [includeVersionRangeText]（Includeタグ自身の`@range`指定）が優先され、無ければ
     * alias宣言側の範囲、どちらも無ければ[VersionRange.Latest]を使う。
     */
    fun resolve(
        target: String,
        includeVersionRangeText: String?,
        imports: List<ImportDeclaration>,
    ): FragmentReference {
        if (target.startsWith(NESTED_PROMPT_PREFIX)) throw NestedPromptNotSupportedException(target)

        val aliased = imports.find { it.alias == target }
        val fragmentKey = aliased?.fragmentKey ?: FragmentKey(target)
        val range = includeVersionRangeText?.let { VersionRange.parse(it) } ?: aliased?.range ?: VersionRange.Latest
        return FragmentReference(fragmentKey, range)
    }
}
