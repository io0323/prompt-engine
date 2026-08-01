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
 * key=value形式で構造化して載せる。[cause]は`javaClass.simpleName`のみを通常の
 * フォーマット引数として文字列補間し、`cause`オブジェクト自体（Throwable）は
 * 一切SLF4J呼び出しへ渡さない（CodeRabbitレビュー指摘: SLF4Jは末尾引数が`Throwable`の
 * 場合、フォーマット済みメッセージに現れなくても`cause.message`とスタックトレース全体を
 * ログイベントに添付して記録する。DB接続情報等インフラ層由来の例外メッセージに秘密情報が
 * 含まれる可能性があるため、`cause.message`はもちろん`cause`オブジェクト自体もログ出力
 * 経路に一切渡さない契約とする。`Slf4jAuditFailureHandlerTest`で添付Throwableが常に
 * `null`であることを固定する）。
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
        )
    }

    companion object {
        private val logger = LoggerFactory.getLogger(Slf4jAuditFailureHandler::class.java)
    }
}
