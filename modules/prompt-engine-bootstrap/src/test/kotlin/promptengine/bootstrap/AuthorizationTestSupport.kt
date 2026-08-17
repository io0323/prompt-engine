package promptengine.bootstrap

import org.springframework.core.convert.converter.Converter
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import promptengine.interfaces.security.CiapAuthAdapter

/**
 * `*ControllerAuthorizationTest`（Issue #115、参照実装は#114の`ExperimentControllerAuthorizationTest`）
 * 共通の軽量認可テストヘルパー。
 *
 * `SecurityMockMvcRequestPostProcessors.jwt()`は既定では`scope`クレームを`CiapAuthAdapter`
 * ではなくSpring標準の`JwtGrantedAuthoritiesConverter`（`SCOPE_`接頭辞を付与する）で
 * `GrantedAuthority`化するため、`.claim("scope", ...)`だけでは
 * `@PreAuthorize("hasAuthority('prompt:write')")`（接頭辞無し、[CiapAuthAdapter]のKDoc参照）
 * と一致しない（PR #114で発見）。[jwtWithScope]は`JwtRequestPostProcessor.authorities(Converter)`
 * で明示的に実[CiapAuthAdapter.convert]を経由させ、`@PreAuthorize`の判定が実際のコードパスを
 * そのまま通るようにする。
 */
private val ciapAuthAdapter = CiapAuthAdapter()

internal fun jwtWithScope(scope: String): JwtRequestPostProcessor {
    val authoritiesConverter =
        Converter<Jwt, Collection<GrantedAuthority>> { source -> ciapAuthAdapter.convert(source).authorities }
    return jwt()
        .jwt { it.claim("scope", scope).subject("user:test") }
        .authorities(authoritiesConverter)
}
