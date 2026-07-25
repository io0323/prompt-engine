package promptengine.domain.prompt

import promptengine.domain.shared.SemVer

/**
 * 実行時のVersion参照方式（設計書§4.4 / §2.13）。
 */
sealed class VersionRef {
    data class Fixed(val semVer: SemVer) : VersionRef()

    data object Latest : VersionRef()

    data class Alias(val name: String) : VersionRef() {
        init {
            require(name.isNotBlank()) { "name must not be blank" }
        }
    }
}
