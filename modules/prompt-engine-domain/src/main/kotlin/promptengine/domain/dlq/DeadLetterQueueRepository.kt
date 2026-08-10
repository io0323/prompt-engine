package promptengine.domain.dlq

/**
 * 実DLQ（`dead_letter_queue`）の永続化ポート（Issue #37、ADR-0026決定2）。
 *
 * Brokerの専用DLQトピックではなくPostgresの専用テーブルを採用した理由はADR-0026決定2を参照
 * （運用者がSQLで中身を確認・再処理でき、購読側ごとの再処理状態（`status`/`retry_count`）を
 * 同じ場所で管理できるため）。
 *
 * 再処理は手動（運用者が起動するコマンド）を前提とし、自動リトライポーラは持たない
 * （M1スコープ外、ADR-0026決定2）。
 */
interface DeadLetterQueueRepository {
    /**
     * [entry]を退避する。同一の`(event_id, subscriber_name)`が既に退避済みなら新しい行を
     * 作らず`retry_count`を加算し`last_failed_at`を更新する（at-least-once配信で同じイベントが
     * 繰り返し失敗してもDLQ行が際限なく増えないようにするため）。
     *
     * **この呼出し自体は失敗させない契約とする。** DLQへの書き込み失敗が本流の
     * イベント処理を巻き込んで落とすと、DLQを設けた意味が失われるため。
     */
    fun enqueue(entry: DeadLetterEntry)

    /**
     * 未処理（`status = 'PENDING'`）の退避件数を返す。退避が発生したことの検知に使う
     * （ADR-0026決定2: Micrometer等のメトリクスバックエンドを新設せず、問い合わせ可能な
     * 件数と退避ごとの構造化ログの2本立てとする）。
     */
    fun pendingCount(): Long
}
