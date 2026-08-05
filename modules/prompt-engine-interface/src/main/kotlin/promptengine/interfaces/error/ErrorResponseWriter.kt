package promptengine.interfaces.error

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import promptengine.interfaces.support.TraceIdFilter

/**
 * 401（`UNAUTHENTICATED`）・403（`PERMISSION_DENIED`、URLパターンレベル）のレスポンス本文を
 * `GlobalExceptionHandler`と同じ設計書§13.3の封筒形式で書き出す（P9c）。
 *
 * Spring SecurityのExceptionTranslationFilterは`DispatcherServlet`より前段で例外を捕捉する
 * ため、`@RestControllerAdvice`（`GlobalExceptionHandler`）はこれらを処理できない。
 * `SecurityConfig`の`AuthenticationEntryPoint`/`AccessDeniedHandler`から本クラスを呼ぶ。
 */
class ErrorResponseWriter(private val objectMapper: ObjectMapper) {
    fun write(
        request: HttpServletRequest,
        response: HttpServletResponse,
        status: HttpStatus,
        code: String,
        message: String,
    ) {
        val body = ErrorResponseDto(ErrorBodyDto(code, message, emptyList(), TraceIdFilter.traceIdOf(request)))
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        objectMapper.writeValue(response.writer, body)
    }
}
