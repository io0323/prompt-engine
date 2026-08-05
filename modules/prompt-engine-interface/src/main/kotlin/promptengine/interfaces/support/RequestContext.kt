package promptengine.interfaces.support

import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken

/**
 * CIAP発行JWT（設計書§13共通仕様）から、Command/Query構築に必要な`actor`を取り出す（P9c）。
 *
 * `actor`はJWTの`sub`クレーム（Subject、CIAPが発行するユーザー/クライアント識別子）とする。
 * `CiapAuthAdapter`は`scope`/`scp`クレームのみを認可判定用に変換するため、Subjectの取得は
 * 別途このヘルパーが担う。
 */
object RequestContext {
    fun actorOf(authentication: JwtAuthenticationToken): String = jwtOf(authentication).subject

    private fun jwtOf(authentication: JwtAuthenticationToken): Jwt = authentication.token
}
