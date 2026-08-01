package promptengine.infrastructure.audit

import org.slf4j.LoggerFactory
import promptengine.domain.audit.AuditFailureHandler
import promptengine.domain.audit.AuditOutcome
import promptengine.domain.audit.AuditRecord

/**
 * [AuditFailureHandler]のSLF4J実装（ADR-0015決定8）。
 *
 * `prompt-engine-application`はSLF4Jへ直接依存できない（P4で追加したArchUnitの
 * フレームワーク隔離規約）ため、この実装を`prompt-engine-infrastructure`に置く。
 *
 * ログ本文には[AuditRecord]のフィールド（traceId・promptKey・mode・outcome）のみを
 * key=value形式で構造化して載せる。[cause]は`javaClass.simpleName`のみを文字列補間し、
 * `cause.message`は補間せずSLF4Jの`(msg, throwable)`オーバーロードにそのまま渡す
 * （スタックトレースはログ基盤側で記録される。[AuditRecord]自体が生のprompt/response
 * 内容を保持しないため通常は`cause.message`が秘密情報を含む経路も無いはずだが、
 * インフラ層由来の例外（DB接続情報等）が将来混入する可能性に備え、組立文字列には
 * 一切含めない）。
 *
 * 実DLQ（キュー・再試行テーブル）は
 * [Issue #37](https://github.com/io0323/prompt-engine/issues/37)で追跡し、
 * ここではログ出力のみを行う（M1では実装しない）。
 */
class Slf4jAuditFailureHandler : AuditFailureHandler {
    override fun handle(
        record: AuditRecord,
        cause: Throwable,
    ) {
        val outcomeLabel =
            when (val outcome = record.outcome) {
                is AuditOutcome.Success -> "Success"
                is AuditOutcome.Failure -> "Failure(errorCode=${outcome.errorCode})"
            }
        logger.error(
            "audit_append_failed traceId={} promptKey={} mode={} outcome={} cause={}",
            record.traceId,
            record.promptKey,
            record.mode,
            outcomeLabel,
            cause.javaClass.simpleName,
            cause,
        )
    }

    companion object {
        private val logger = LoggerFactory.getLogger(Slf4jAuditFailureHandler::class.java)
    }
}
