package promptengine.interfaces.support

import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken

/**
 * CIAP発行JWT（設計書§13共通仕様）から、Command/Query構築に必要な`actor`を取り出す（P9c）。
 *
 * `actor`はJWTの`sub`クレーム（Subject、CIAPが発行するユーザー/クライアント識別子）とする。
 * `CiapAuthAdapter`は`scope`/`scp`クレームのみを認可判定用に変換するため、Subjectの取得は
 * 別途このヘルパーが担う。
 *
 * `sub`は`SecurityConfig`の`JwtDecoder`が必須クレームとして検証しない（CodeRabbitレビュー
 * 指摘）ため、認証自体は成功しても`sub`が欠落したJWTが本メソッドまで到達し得る。その場合、
 * `Jwt.getSubject()`のnull許容プラットフォーム型をそのまま非null`String`として扱うと未分類の
 * `NullPointerException`（`GlobalExceptionHandler`の`handleUnclassified`経由で500）になり、
 * クライアントに原因が伝わらない。ここで明示的に検証し、`GlobalExceptionHandler`の
 * `IllegalArgumentException`ハンドラ経由で400（`INVALID_REQUEST`）として返す
 * （401にしない理由: Spring SecurityのJWT署名検証自体は既に成功しており、認証は成功して
 * いる。欠落しているのは`actor`帰属に必要な追加クレームであり、リクエスト自体の不備として
 * 扱う方が実態に近い）。
 */
object RequestContext {
    fun actorOf(authentication: JwtAuthenticationToken): String {
        val subject = jwtOf(authentication).subject
        require(!subject.isNullOrBlank()) { "JWT is missing required 'sub' claim" }
        return subject
    }

    private fun jwtOf(authentication: JwtAuthenticationToken): Jwt = authentication.token
}
