package promptengine.interfaces.support

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import java.time.Instant

class RequestContextTest {
    private fun tokenWithSubject(subject: String?): JwtAuthenticationToken {
        val builder =
            Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .issuedAt(Instant.EPOCH)
                .expiresAt(Instant.EPOCH.plusSeconds(60))
                .claim("dummy", "value")
        if (subject != null) builder.subject(subject)
        return JwtAuthenticationToken(builder.build())
    }

    @Test
    fun `sub claimがあればactorとして返す`() {
        RequestContext.actorOf(tokenWithSubject("user:alice")) shouldBe "user:alice"
    }

    @Test
    fun `sub claimが無ければIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> {
            RequestContext.actorOf(tokenWithSubject(null))
        }
    }

    @Test
    fun `sub claimが空文字ならIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> {
            RequestContext.actorOf(tokenWithSubject(""))
        }
    }
}
