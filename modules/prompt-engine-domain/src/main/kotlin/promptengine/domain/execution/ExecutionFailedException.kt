package promptengine.domain.execution

/**
 * [ExecutionAdapter.execute]が最終的に失敗した（設計書§13.3 `EXECUTION_FAILED`、
 * §2.6ステージ9「APAPエラー透過」、ADR-0014決定2・決定7）。
 *
 * [errorType]・[retryCount]のみを保持し、生のprompt/response内容は一切保持しない
 * （ログ・例外メッセージへの秘密情報混入を型レベルで構造的に防ぐ、ADR-0014決定9）。
 */
class ExecutionFailedException(
    val errorType: ExecutionErrorType,
    val retryCount: Int,
    cause: Throwable? = null,
) : RuntimeException("EXECUTION_FAILED: errorType=$errorType retryCount=$retryCount", cause)
