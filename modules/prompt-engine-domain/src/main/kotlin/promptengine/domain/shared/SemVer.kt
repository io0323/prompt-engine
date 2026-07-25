package promptengine.domain.shared

/**
 * Prompt/Template/FragmentのVersion採番（設計書§4.4）。
 */
data class SemVer(val major: Int, val minor: Int, val patch: Int) : Comparable<SemVer> {
    init {
        require(major >= 0) { "major must not be negative: $major" }
        require(minor >= 0) { "minor must not be negative: $minor" }
        require(patch >= 0) { "patch must not be negative: $patch" }
    }

    override fun toString(): String = "$major.$minor.$patch"

    override fun compareTo(other: SemVer): Int =
        compareValuesBy(
            this,
            other,
            SemVer::major,
            SemVer::minor,
            SemVer::patch,
        )
}
