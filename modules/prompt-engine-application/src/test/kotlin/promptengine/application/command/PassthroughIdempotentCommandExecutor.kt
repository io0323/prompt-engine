package promptengine.application.command

import promptengine.domain.shared.IdempotentCommandExecutor

/**
 * テスト用フェイク。冪等性の記録を一切行わず、常に[command]/[operation]をそのまま実行する。
 * [JdbcIdempotentCommandExecutorIntegrationTest][promptengine.infrastructure.persistence]が
 * 実DBでの冪等性動作そのものを検証するため、ハンドラ単体テストではこちらでビジネスロジックだけを
 * 検証する。
 */
class PassthroughIdempotentCommandExecutor : IdempotentCommandExecutor {
    override fun <T : Any> executeInTransaction(
        idempotencyKey: String?,
        requestFingerprint: String,
        resultType: Class<T>,
        command: () -> T,
    ): T = command()

    override fun <T : Any> executeLongRunning(
        idempotencyKey: String?,
        requestFingerprint: String,
        resultType: Class<T>,
        operation: () -> T,
    ): T = operation()
}
