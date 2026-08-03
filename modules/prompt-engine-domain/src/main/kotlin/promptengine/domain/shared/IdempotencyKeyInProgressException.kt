package promptengine.domain.shared

/**
 * 同一`Idempotency-Key`の処理が完了する前（`status = IN_PROGRESS`）に再送されたときに投げる
 * （設計書§13.3 `IDEMPOTENCY_KEY_IN_PROGRESS`、409）。[IdempotentCommandExecutor.executeLongRunning]
 * の2フェーズ実行中の再送で発生し得る。
 */
class IdempotencyKeyInProgressException(val idempotencyKey: String, cause: Throwable? = null) :
    IllegalStateException("idempotency key '$idempotencyKey' is still being processed", cause)
