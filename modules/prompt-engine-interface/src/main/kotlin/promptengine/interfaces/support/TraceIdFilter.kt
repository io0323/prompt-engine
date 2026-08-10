package promptengine.interfaces.support

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * `X-Trace-Id`（設計書§13共通仕様）の受信・伝播を担うServlet Filter（P9c）。
 *
 * リクエストヘッダに`X-Trace-Id`が無ければ新規UUIDを生成する。生成/受信した値は
 * [request]属性[TRACE_ID_ATTRIBUTE]に格納し、レスポンスヘッダにも即座に反映する
 * （Controller到達前・例外発生時のいずれでもクライアントへ返るレスポンスに
 * `X-Trace-Id`が付くようにするため）。
 *
 * SecurityFilterChainより前段（`FilterRegistrationBean`ではなくSecurityの
 * `HttpSecurity.addFilterBefore`）に置くことで、認証エラー（401/403、`SecurityConfig`の
 * entry point/handler）のレスポンスにも同じtraceIdが使われる。
 *
 * MDCへ`traceId`を投入する（設計書§2.15「相関ID」、ADR-0027決定3）ことで、このリクエスト
 * 処理中に出力される全ログ行（`SanitizingJsonEncoder`がJSON化）へ自動的に載る。
 * `finally`で必ず`MDC.remove`する（スレッドプールでスレッドが使い回されるため、
 * 除去しないと次のリクエストへ前のtraceIdが漏れ残る）。
 */
class TraceIdFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val traceId = request.getHeader(HEADER)?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        request.setAttribute(TRACE_ID_ATTRIBUTE, traceId)
        response.setHeader(HEADER, traceId)
        MDC.put(MDC_TRACE_ID_KEY, traceId)
        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove(MDC_TRACE_ID_KEY)
        }
    }

    companion object {
        const val MDC_TRACE_ID_KEY = "traceId"
        const val HEADER = "X-Trace-Id"
        const val TRACE_ID_ATTRIBUTE = "promptengine.traceId"

        /** [TraceIdFilter]が設定済みの[request]属性からtraceIdを取り出す。Filter未適用時のみheaderへフォールバックする。 */
        fun traceIdOf(request: HttpServletRequest): String =
            (request.getAttribute(TRACE_ID_ATTRIBUTE) as String?)
                ?: request.getHeader(HEADER)?.takeIf { it.isNotBlank() }
                ?: UUID.randomUUID().toString()
    }
}
