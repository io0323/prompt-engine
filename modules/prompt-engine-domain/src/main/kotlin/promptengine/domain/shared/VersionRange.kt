package promptengine.domain.shared

/**
 * extendsのVersion範囲（設計書§15.1/§15.3 `extends: <templateKey>[@versionRange]`）。
 * `imports`のSemVer範囲（§15.4）にも共通して使う値型。
 *
 * `parse`/`toRangeText` は互いに往復する（`parse(text).toRangeText() == text`）。
 * DBへの保存・DSLフロントマターからの復元のいずれも、この2関数を唯一の変換経路とする
 * （ADR-0009「保存された参照 == ソースをパースした結果」の保証）。
 */
sealed class VersionRange {
    /** [candidate] がこの範囲の条件を満たすかどうか。 */
    abstract fun matches(candidate: SemVer): Boolean

    /** DB保存・DSL往復用のテキスト表現。範囲指定なし（[Latest]）は `null`。 */
    abstract fun toRangeText(): String?

    /** 範囲指定なし（`extends: key`のように`@versionRange`を伴わない）。全Versionにマッチする。 */
    data object Latest : VersionRange() {
        override fun matches(candidate: SemVer): Boolean = true

        override fun toRangeText(): String? = null
    }

    /** `^2`（同一majorの最新）。 */
    data class CaretMajor(val major: Int) : VersionRange() {
        init {
            require(major >= 0) { "major must not be negative: $major" }
        }

        override fun matches(candidate: SemVer): Boolean = candidate.major == major

        override fun toRangeText(): String = "^$major"
    }

    /** `1.3.0`（完全一致）。 */
    data class Exact(val semVer: SemVer) : VersionRange() {
        override fun matches(candidate: SemVer): Boolean = candidate == semVer

        override fun toRangeText(): String = semVer.toString()
    }

    companion object {
        private val CARET_MAJOR_PATTERN = Regex("^\\^(\\d+)$")
        private val EXACT_SEMVER_PATTERN = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)$")

        /**
         * `text` が `null`・空文字列なら[Latest]、`^<major>` 形式なら[CaretMajor]、
         * `major.minor.patch` 形式なら[Exact]として解釈する。いずれの形式にも
         * 一致しない場合は `IllegalArgumentException` を投げる。
         */
        fun parse(text: String?): VersionRange =
            if (text.isNullOrBlank()) {
                Latest
            } else {
                val caretMatch = CARET_MAJOR_PATTERN.matchEntire(text)
                if (caretMatch != null) {
                    val (major) = caretMatch.destructured
                    CaretMajor(major.toInt())
                } else {
                    parseExact(text)
                }
            }

        private fun parseExact(text: String): Exact {
            val match =
                EXACT_SEMVER_PATTERN.matchEntire(text)
                    ?: throw IllegalArgumentException("invalid version range: $text")
            val (major, minor, patch) = match.destructured
            return Exact(SemVer(major.toInt(), minor.toInt(), patch.toInt()))
        }
    }
}
