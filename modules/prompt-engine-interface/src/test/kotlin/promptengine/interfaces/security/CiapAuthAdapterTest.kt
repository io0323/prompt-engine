package promptengine.interfaces.security

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jwt.Jwt
import java.time.Instant

class CiapAuthAdapterTest {
    private val adapter = CiapAuthAdapter()

    private fun jwt(claims: Map<String, Any?>): Jwt =
        Jwt.withTokenValue("token")
            .header("alg", "RS256")
            .issuedAt(Instant.EPOCH)
            .expiresAt(Instant.EPOCH.plusSeconds(60))
            .claims { it.putAll(claims.filterValues { v -> v != null }.mapValues { (_, v) -> v!! }) }
            .build()

    @Test
    fun `scopeが空白区切り文字列の場合はauthorityへ分割する`() {
        val authorities = adapter.convert(jwt(mapOf("scope" to "prompt:read prompt:write"))).authorities

        authorities.map { it.authority }.toSet() shouldBe setOf("prompt:read", "prompt:write")
    }

    @Test
    fun `scopeがJSON配列で来ても分割済みとして扱う`() {
        val authorities = adapter.convert(jwt(mapOf("scope" to listOf("prompt:read", "prompt:execute")))).authorities

        authorities.map { it.authority }.toSet() shouldBe setOf("prompt:read", "prompt:execute")
    }

    @Test
    fun `scopeが無ければscpクレーム（文字列配列）にフォールバックする`() {
        val authorities = adapter.convert(jwt(mapOf("scp" to listOf("audit:read")))).authorities

        authorities.map { it.authority }.toSet() shouldBe setOf("audit:read")
    }

    @Test
    fun `scope-scpどちらも無ければ権限は空になる`() {
        val authorities = adapter.convert(jwt(mapOf("sub" to "client"))).authorities

        authorities.isEmpty() shouldBe true
    }
}
