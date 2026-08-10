package promptengine.domain.audit

/**
 * [AuditRepository.append]が失敗した際の処理（設計書§2.6ステージ12「失敗時はDLQ退避」、
 * ADR-0015決定8）。
 *
 * `AuditStage`（`prompt-engine-application`）はこのInterfaceのみを参照し、ログ出力の
 * 実装詳細（SLF4J等）を知らない。実装（`Slf4jAuditFailureHandler`）は
 * `prompt-engine-infrastructure`に置く（`prompt-engine-application`がSLF4Jへ直接依存する
 * ことを禁じるArchUnit規約を維持するため）。
 *
 * 実DLQは[Issue #37](https://github.com/io0323/prompt-engine/issues/37)としてM1で先送りされ、
 * `Slf4jAuditFailureHandler`が構造化ログを出すだけの暫定実装だったが、P10b（ADR-0026決定2）で
 * `dead_letter_queue`テーブルへの退避を行う`DeadLetterQueueAuditFailureHandler`を追加し
 * Issue #37をクローズした。既定の配線はDLQ実装であり、`Slf4jAuditFailureHandler`は
 * DLQ実装が内部で使う「退避を検知可能にするための構造化ログ」の担い手として残る。
 */
interface AuditFailureHandler {
    /** [record]の追記に失敗したことを記録する。この呼出し自体は失敗させない契約とする。 */
    fun handle(
        record: AuditRecord,
        cause: Throwable,
    )
}
