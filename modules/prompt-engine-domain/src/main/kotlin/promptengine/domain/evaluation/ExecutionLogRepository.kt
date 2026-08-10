package promptengine.domain.evaluation

import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.SemVer
import java.time.Instant

/**
 * 実行ログ（`execution_logs`、設計書§12）の追記専用ストア（ADR-0026決定1）。
 *
 * 参照系は「直近に実行があったか」の問い合わせ1つに絞る。`ArchiveHandler`のガード
 * （Issue #48、ADR-0026決定5）が必要とするのは真偽値のみであり、実行ログ本体を
 * アプリケーション層へ持ち出す必要が無いため。
 */
interface ExecutionLogRepository {
    /**
     * [entry]を追記し、実際に行が挿入されたなら`true`、同一`event_id`が既にあり
     * 何も書かれなかったなら`false`を返す（`ON CONFLICT (event_id) DO NOTHING`、
     * ADR-0025決定8）。再配信は例外にしない。
     */
    fun append(entry: ExecutionLogEntry): Boolean

    /**
     * [key] / [semVer]が指すVersionに、[since]以降の実行記録が1件でも存在するかを返す。
     * Versionが存在しない場合も`false`を返す（存在判定は呼出元の責務ではない）。
     */
    fun hasExecutionSince(
        key: PromptKey,
        semVer: SemVer,
        since: Instant,
    ): Boolean
}
