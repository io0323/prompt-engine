package promptengine.domain.shared

/**
 * 同一`Idempotency-Key`で、過去のリクエストと異なる内容（[IdempotentCommandExecutor]の
 * `requestFingerprint`が不一致）の再送を検知したときに投げる（設計書§13.3
 * `IDEMPOTENCY_KEY_CONFLICT`、409）。
 */
class IdempotencyKeyConflictException(val idempotencyKey: String) :
    IllegalStateException("idempotency key '$idempotencyKey' was already used with a different request")
