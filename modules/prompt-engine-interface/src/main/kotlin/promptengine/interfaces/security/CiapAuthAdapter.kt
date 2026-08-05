package promptengine.interfaces.security

import org.springframework.core.convert.converter.Converter
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken

/**
 * CIAP（設計書§1、認証・認可基盤）が発行するJWTを検証済みの[Jwt]から、Spring Securityの
 * 認可判定（`@PreAuthorize`）が使う[GrantedAuthority]へ変換する（設計書§13共通仕様
 * 「認可はCIAP発行のBearerトークン(スコープ: prompt:read|write|review|approve|publish|
 * execute|admin, audit:read)」）。
 *
 * JWTの署名検証・有効期限チェック自体はSpring SecurityのResource Server機構
 * （`JwtDecoder`、`CiapSecurityConfig`が結線）が行う。本クラスはPE自身がユーザー管理を
 * 持たない（CLAUDE.md「CIAP: 認証・認可 → PEは検証のみ」）という責務分離を反映し、
 * クレームからのスコープ抽出・権限マッピングのみを担う。
 *
 * スコープクレームは`scope`（空白区切り文字列、OAuth2標準）または`scp`（文字列配列、一部IdP方言）
 * のいずれかを受け付ける。[GrantedAuthority]の値は設計書のスコープ文字列（例: `"prompt:read"`）
 * そのままとし、Spring標準の`SCOPE_`接頭辞は付与しない（Controller側が
 * `@PreAuthorize("hasAuthority('prompt:read')")`のように設計書の表記をそのまま書けるようにするため）。
 */
class CiapAuthAdapter : Converter<Jwt, AbstractAuthenticationToken> {
    override fun convert(source: Jwt): AbstractAuthenticationToken {
        val authorities = extractScopes(source).map { SimpleGrantedAuthority(it) }
        return JwtAuthenticationToken(source, authorities)
    }

    private fun extractScopes(jwt: Jwt): List<String> {
        val scopeClaim = jwt.getClaimAsString(SCOPE_CLAIM)
        if (!scopeClaim.isNullOrBlank()) return scopeClaim.split(" ").filter { it.isNotBlank() }

        val scpClaim = jwt.getClaimAsStringList(SCP_CLAIM)
        return scpClaim ?: emptyList()
    }

    private companion object {
        const val SCOPE_CLAIM = "scope"
        const val SCP_CLAIM = "scp"
    }
}
