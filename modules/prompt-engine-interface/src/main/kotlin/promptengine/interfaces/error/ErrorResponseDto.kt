package promptengine.interfaces.error

/** 設計書§13.3のエラーレスポンス封筒 `{"error": {code, message, details, traceId}}`。 */
data class ErrorResponseDto(val error: ErrorBodyDto)

data class ErrorBodyDto(
    val code: String,
    val message: String,
    val details: List<ErrorDetailDto> = emptyList(),
    val traceId: String,
)

/** 設計書§13.3 `details[]`の1件 `{rule, path, severity}`。 */
data class ErrorDetailDto(
    val rule: String,
    val path: String,
    val severity: String,
    val message: String? = null,
)
