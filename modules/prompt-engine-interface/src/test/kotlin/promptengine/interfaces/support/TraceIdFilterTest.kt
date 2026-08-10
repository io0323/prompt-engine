package promptengine.interfaces.support

import io.kotest.matchers.shouldBe
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

private val NOOP_CHAIN = FilterChain { _, _ -> }

/**
 * [TraceIdFilter]の単体テスト（P9c、MDC投入はADR-0027決定3で追加）。
 */
class TraceIdFilterTest {
    private val filter = TraceIdFilter()

    @AfterEach
    fun tearDown() {
        MDC.clear()
    }

    @Test
    fun `X-Trace-Idヘッダが無ければ生成しrequest属性とレスポンスヘッダへ反映する`() {
        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, NOOP_CHAIN)

        val traceId = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE) as String
        traceId.isNotBlank() shouldBe true
        response.getHeader(TraceIdFilter.HEADER) shouldBe traceId
    }

    @Test
    fun `X-Trace-Idヘッダがあればそのまま使う`() {
        val request = MockHttpServletRequest().apply { addHeader(TraceIdFilter.HEADER, "trace-given") }
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, NOOP_CHAIN)

        request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE) shouldBe "trace-given"
        response.getHeader(TraceIdFilter.HEADER) shouldBe "trace-given"
    }

    @Test
    fun `filterChain実行中はMDCにtraceIdが積まれ完了後に除去される`() {
        val request = MockHttpServletRequest().apply { addHeader(TraceIdFilter.HEADER, "trace-mdc") }
        val response = MockHttpServletResponse()
        var mdcDuringChain: String? = null
        val chain = FilterChain { _, _ -> mdcDuringChain = MDC.get(TraceIdFilter.MDC_TRACE_ID_KEY) }

        filter.doFilter(request, response, chain)

        mdcDuringChain shouldBe "trace-mdc"
        MDC.get(TraceIdFilter.MDC_TRACE_ID_KEY) shouldBe null
    }

    @Test
    fun `filterChainが例外を投げてもMDCは除去される`() {
        val request = MockHttpServletRequest().apply { addHeader(TraceIdFilter.HEADER, "trace-error") }
        val response = MockHttpServletResponse()
        val chain = FilterChain { _, _ -> error("downstream failure") }

        runCatching { filter.doFilter(request, response, chain) }

        MDC.get(TraceIdFilter.MDC_TRACE_ID_KEY) shouldBe null
    }
}
