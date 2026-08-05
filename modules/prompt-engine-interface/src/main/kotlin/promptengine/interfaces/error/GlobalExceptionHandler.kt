package promptengine.interfaces.error

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException
import promptengine.application.error.ErrorCodeResolver
import promptengine.application.pipeline.StageErrorMapper
import promptengine.interfaces.support.TraceIdFilter

/**
 * 設計書§13.3の全エラーコードをHTTPステータスへ写像する唯一の集約点（P9c）。
 *
 * `prompt-engine-interface`は`promptengine.domain..`に依存できない（ArchUnitルール
 * 「prompt-engine-interfaceはprompt-engine-applicationのみを呼ぶ」）ため、domain例外型を
 * `@ExceptionHandler`の引数に直接指定できない。型判定は[ErrorCodeResolver]
 * （`prompt-engine-application`、[promptengine.application.pipeline.StageErrorMapper]へ
 * 委譲する薄いラッパー）に一任し、本クラスは`Exception`のcatch-all（[handleUnclassified]）
 * 経由でコード文字列のみを受け取る。個別の`@ExceptionHandler`を宣言するのは、Spring/Java
 * 標準の例外型（Bean Validation・リクエストバインド・DB一意制約違反）のみ。
 *
 * 401（`UNAUTHENTICATED`）・403（`PERMISSION_DENIED`、URLパターンレベルの拒否）は
 * `SecurityConfig`の`AuthenticationEntryPoint`/`AccessDeniedHandler`が担い、本クラスの
 * 対象外（Spring SecurityのExceptionTranslationFilterがDispatcherServletより前段で
 * 処理するため、`@RestControllerAdvice`まで到達しない）。
 */
@RestControllerAdvice
class GlobalExceptionHandler {
    private val logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    /** DB一意制約違反(例: 同一PromptKeyの並行作成)。JdbcTemplateがSQL例外から変換する。 */
    @ExceptionHandler(DuplicateKeyException::class)
    fun handleDuplicateKey(
        ex: DuplicateKeyException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponseDto> = respond(HttpStatus.CONFLICT, ErrorCodes.DUPLICATE_KEY, ex.message, request)

    /** `@Valid`（Bean Validation、`@RequestBody`）の検証失敗。 */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponseDto> {
        val details =
            ex.bindingResult.fieldErrors.map {
                ErrorDetailDto(
                    rule = it.code ?: "Invalid",
                    path = "$.${it.field}",
                    severity = "ERROR",
                    message = it.defaultMessage,
                )
            }
        return respond(
            HttpStatus.BAD_REQUEST,
            StageErrorMapper.VALIDATION_FAILED,
            "request validation failed",
            request,
            details,
        )
    }

    /** `@Validated`（`@RequestParam`/`@PathVariable`）の検証失敗。 */
    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraintViolation(
        ex: ConstraintViolationException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponseDto> {
        val details =
            ex.constraintViolations.map {
                ErrorDetailDto(
                    rule = it.constraintDescriptor.annotation.annotationClass.simpleName ?: "Invalid",
                    path = "$.${it.propertyPath}",
                    severity = "ERROR",
                    message = it.message,
                )
            }
        return respond(
            HttpStatus.BAD_REQUEST,
            StageErrorMapper.VALIDATION_FAILED,
            "request validation failed",
            request,
            details,
        )
    }

    @ExceptionHandler(
        HttpMessageNotReadableException::class,
        MissingServletRequestParameterException::class,
        MethodArgumentTypeMismatchException::class,
        IllegalArgumentException::class,
    )
    fun handleMalformedRequest(
        ex: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponseDto> =
        respond(HttpStatus.BAD_REQUEST, StageErrorMapper.INVALID_REQUEST, ex.message, request)

    /**
     * どの`@RequestMapping`にもマッチしないURLパス（9c初回のE2E確認で発覚。
     * `handleUnclassified`のcatch-allに落ち、`INTERNAL_ERROR`(500)へ丸められていた）。
     * [ErrorCodes.NOT_FOUND]のKDoc参照（§13.3の表の対象外である理由）。
     */
    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResourceFound(
        ex: NoResourceFoundException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponseDto> = respond(HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND, ex.message, request)

    /**
     * 上記いずれにも該当しない例外の最終フォールバック。[ErrorCodeResolver]がCRUD系例外・
     * Pipeline Stage例外（[promptengine.application.pipeline.StageErrorMapper]経由）の
     * 両方を判定する。真に未分類の例外は`INTERNAL_ERROR`(500)としてログに記録する。
     */
    @ExceptionHandler(Exception::class)
    fun handleUnclassified(
        ex: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponseDto> {
        val code = ErrorCodeResolver.resolve(ex)
        val status = ErrorCodes.statusFor(code)
        if (status.is5xxServerError) {
            logger.error("unhandled exception mapped to {} ({})", code, status, ex)
        }
        val details =
            ErrorCodeResolver.detailsFor(ex).map { ErrorDetailDto(it.rule, it.path, it.severity, it.message) }
        return respond(status, code, ex.message, request, details)
    }

    private fun respond(
        status: HttpStatus,
        code: String,
        message: String?,
        request: HttpServletRequest,
        details: List<ErrorDetailDto> = emptyList(),
    ): ResponseEntity<ErrorResponseDto> {
        val traceId = TraceIdFilter.traceIdOf(request)
        val body = ErrorResponseDto(ErrorBodyDto(code, message ?: code, details, traceId))
        return ResponseEntity.status(status).body(body)
    }
}
