package promptengine.bootstrap.config

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import promptengine.interfaces.error.ErrorResponseWriter
import promptengine.interfaces.security.CiapAuthAdapter
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey

/**
 * Spring Security Resource Server（JWT）の配線（設計書§13共通仕様、P9c）。
 *
 * JWTの署名検証は[jwtDecoder]が担う。`pe.ciap.jwks-uri`が設定されていれば実CIAPのJWKS
 * エンドポイントで検証する。未設定の場合はローカル開発・テスト用に起動時生成した
 * RSA鍵ペアの公開鍵で検証する自己完結モードにフォールバックする（この鍵の秘密鍵は
 * どこにも公開されないため、実運用ではこの経路で有効なJWTを発行する手段が無い＝
 * 誤って本番運用してしまうこと自体を防ぐ設計。テストは`spring-security-test`の
 * `jwt()`postProcessorでJwtDecoderをバイパスして検証する）。
 *
 * スコープ→権限の変換は[CiapAuthAdapter]（`prompt-engine-interface`）が担う。認可判定
 * （どのエンドポイントがどのスコープを要求するか）はController側の`@PreAuthorize`に委ねる
 * （[EnableMethodSecurity]）。
 *
 * 401（`UNAUTHENTICATED`）とURLパターンレベル（`authorizeHttpRequests`）の403は
 * [authenticationEntryPoint]/[accessDeniedHandler]が設計書§13.3の封筒形式で書き出す
 * （Spring SecurityのExceptionTranslationFilterが`DispatcherServlet`より前段で処理するため、
 * `GlobalExceptionHandler`（`@RestControllerAdvice`）はこれらを扱えない）。**メソッドレベルの
 * `@PreAuthorize`拒否（本アプリの`Controller`が実際に使う認可経路）はこの限りではなく、
 * `GlobalExceptionHandler.handleAccessDenied`が同一の403/`PERMISSION_DENIED`封筒を返す
 * （ADR-0034の認可テストで発見。この2経路は同じ結果になるよう意図的に揃えてある）。
 */
@Configuration
@EnableMethodSecurity
class SecurityConfig {
    @Bean
    fun jwtDecoder(
        @Value("\${pe.ciap.jwks-uri:}") jwksUri: String,
    ): JwtDecoder =
        if (jwksUri.isNotBlank()) {
            NimbusJwtDecoder.withJwkSetUri(jwksUri).build()
        } else {
            NimbusJwtDecoder.withPublicKey(devPublicKey()).build()
        }

    @Bean
    fun ciapAuthAdapter(): CiapAuthAdapter = CiapAuthAdapter()

    @Bean
    fun errorResponseWriter(objectMapper: ObjectMapper): ErrorResponseWriter = ErrorResponseWriter(objectMapper)

    @Bean
    fun authenticationEntryPoint(errorResponseWriter: ErrorResponseWriter): AuthenticationEntryPoint =
        AuthenticationEntryPoint { request, response, _ ->
            errorResponseWriter.write(
                request,
                response,
                HttpStatus.UNAUTHORIZED,
                "UNAUTHENTICATED",
                "authentication required",
            )
        }

    @Bean
    fun accessDeniedHandler(errorResponseWriter: ErrorResponseWriter): AccessDeniedHandler =
        AccessDeniedHandler { request, response, _ ->
            errorResponseWriter.write(
                request,
                response,
                HttpStatus.FORBIDDEN,
                "PERMISSION_DENIED",
                "insufficient scope",
            )
        }

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        ciapAuthAdapter: CiapAuthAdapter,
        entryPoint: AuthenticationEntryPoint,
        deniedHandler: AccessDeniedHandler,
    ): SecurityFilterChain {
        http {
            csrf { disable() }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
            authorizeHttpRequests {
                // `/actuator/health/**`（`/**`は末尾スラッシュなしの`/actuator/health`自体にも
                // マッチする）。Kubernetesの liveness/readiness プローブ
                // （`/actuator/health/liveness`・`/actuator/health/readiness`、P11の
                // deploy/helm/prompt-engine）は認証を持たずkubeletから直接叩かれるため、
                // サブパスもpermitAllにしないと全PodがCrashLoopBackOffする（P11で発見・修正）。
                authorize("/actuator/health/**", permitAll)
                authorize("/actuator/info", permitAll)
                // `/v3/api-docs/**`だけでは`/v3/api-docs`本体・`/v3/api-docs.yaml`
                // （末尾が`/`で終わらない、springdocの実際のエンドポイント）にAntPathMatcherが
                // マッチしないため401になる（`generateOpenApiDocs`実行時に判明、Issue #13）。
                authorize("/v3/api-docs**", permitAll)
                authorize("/swagger-ui/**", permitAll)
                authorize("/swagger-ui.html", permitAll)
                authorize(anyRequest, authenticated)
            }
            exceptionHandling {
                authenticationEntryPoint = entryPoint
                accessDeniedHandler = deniedHandler
            }
            oauth2ResourceServer {
                jwt {
                    jwtAuthenticationConverter = ciapAuthAdapter
                }
                authenticationEntryPoint = entryPoint
            }
        }
        return http.build()
    }

    /**
     * ローカル開発・テスト専用のフォールバック鍵。プロセス起動ごとに新規生成するため、
     * このプロセスの再起動をまたいでJWTを使い回すことはできない（意図的な制約）。
     */
    private fun devPublicKey(): RSAPublicKey {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(DEV_KEY_SIZE) }.generateKeyPair()
        return keyPair.public as RSAPublicKey
    }

    private companion object {
        const val DEV_KEY_SIZE = 2048
    }
}
