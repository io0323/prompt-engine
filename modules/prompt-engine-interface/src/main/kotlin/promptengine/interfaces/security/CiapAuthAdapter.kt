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
 * スコープクレームは`scope`（OAuth2標準）または`scp`（一部IdP方言）のいずれかを受け付ける。
 * `scope`自体、OAuth2標準の空白区切り文字列（RFC 8693等）とJSON配列（一部IdPの方言、CIAPが
 * 将来この形式で発行する可能性）のいずれで来ても対応する必要がある（CodeRabbitレビュー指摘:
 * `Jwt.getClaimAsString`は配列クレームに対し例外を投げ、認可判定が静かに全権限無しへ
 * フォールバックしていた）。[GrantedAuthority]の値は設計書のスコープ文字列（例:
 * `"prompt:read"`）そのままとし、Spring標準の`SCOPE_`接頭辞は付与しない（Controller側が
 * `@PreAuthorize("hasAuthority('prompt:read')")`のように設計書の表記をそのまま書けるようにするため）。
 */
class CiapAuthAdapter : Converter<Jwt, AbstractAuthenticationToken> {
    override fun convert(source: Jwt): AbstractAuthenticationToken {
        val authorities = extractScopes(source).map { SimpleGrantedAuthority(it) }
        return JwtAuthenticationToken(source, authorities)
    }

    private fun extractScopes(jwt: Jwt): List<String> {
        val scopeScopes = extractScopeClaim(jwt.claims[SCOPE_CLAIM])
        if (scopeScopes.isNotEmpty()) return scopeScopes

        val scpClaim = jwt.getClaimAsStringList(SCP_CLAIM)
        return scpClaim ?: emptyList()
    }

    /** `scope`クレームの型（文字列/文字列配列/未設定）を判別してから読む（クラスKDoc参照）。 */
    private fun extractScopeClaim(scopeClaim: Any?): List<String> =
        when (scopeClaim) {
            is String -> scopeClaim.split(" ").filter { it.isNotBlank() }
            is List<*> -> scopeClaim.filterIsInstance<String>().filter { it.isNotBlank() }
            else -> emptyList()
        }

    private companion object {
        const val SCOPE_CLAIM = "scope"
        const val SCP_CLAIM = "scp"
    }
}
