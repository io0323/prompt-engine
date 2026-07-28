package promptengine.domain.fragment

import java.security.MessageDigest

/**
 * FragmentVersionが保持するDSLソースとそのSHA-256ハッシュ（設計書§4.4のPromptContentと同型）。
 */
data class FragmentContent(val source: String) {
    init {
        require(source.isNotBlank()) { "source must not be blank" }
    }

    val contentHash: String = sha256(source)

    private companion object {
        fun sha256(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
        }
    }
}
