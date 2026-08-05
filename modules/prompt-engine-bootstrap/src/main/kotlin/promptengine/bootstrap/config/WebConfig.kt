package promptengine.bootstrap.config

import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import promptengine.interfaces.support.TraceIdFilter

/**
 * `X-Trace-Id`（設計書§13共通仕様）を、Spring Securityのフィルタチェーンより前段で
 * 設定するための配線（P9c）。
 *
 * [TraceIdFilter]をSpring Bootの`FilterRegistrationBean`で最優先度に登録することで、
 * サーブレットコンテナのフィルタチェーン上でSpring Securityの`FilterChainProxy`
 * （それ自体が単一のServlet Filterとしてコンテナに登録される）より外側にラップされる。
 * これにより、認証・認可エラー（401/403、`SecurityConfig`のentry point/handler）の
 * レスポンスにも`X-Trace-Id`が設定される。
 */
@Configuration
class WebConfig {
    @Bean
    fun traceIdFilterRegistration(): FilterRegistrationBean<TraceIdFilter> =
        FilterRegistrationBean(TraceIdFilter()).apply {
            order = Ordered.HIGHEST_PRECEDENCE
            urlPatterns = listOf("/*")
        }
}
