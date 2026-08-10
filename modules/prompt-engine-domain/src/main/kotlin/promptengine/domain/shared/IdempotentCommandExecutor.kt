package promptengine.domain.shared

/**
 * `Idempotency-Key`（設計書§13.1、全POST）の記録と再送検知を担うDomain Service（P9b）。
 *
 * CRUD系コマンドと長時間操作（`execute`等、APAP呼出を含む）で必要な整合性の取り方が異なるため、
 * メソッドを分ける。
 *
 * - [executeInTransaction]: キー予約・[command]実行・完了記録を1トランザクションで行う。
 *   DB操作のみで完結するCommand（Prompt作成/メタデータ更新/Archive/Version作成/
 *   ライフサイクル遷移/エイリアス設定、compile/render）向け。
 * - [executeLongRunning]: 短いトランザクションでキー予約 → トランザクション外で[operation]実行
 *   → 短いトランザクションで結果記録、の2フェーズで行う。[operation]がAPAP等の外部I/Oを含み
 *   数秒〜数十秒かかり得る場合（`execute`）に、DBトランザクションでコネクションを長時間
 *   保持しないための設計（P9bレビュー指摘）。
 *
 * 両メソッドとも、同一[idempotencyKey]・同一[requestFingerprint]で過去に`COMPLETED`済みなら
 * [command]/[operation]を再実行せず保存済み結果を返す。同一キー・異なるfingerprintの再送は
 * [IdempotencyKeyConflictException]を、同一キーが`IN_PROGRESS`中の再送は
 * [IdempotencyKeyInProgressException]を投げる。[idempotencyKey]が`null`の場合は
 * 冪等性を保証せず、都度[command]/[operation]を実行する。
 *
 * **クラッシュ後の予約回収（[Issue #50](https://github.com/io0323/prompt-engine/issues/50)、ADR-0027）**:
 * [executeLongRunning]は[operation]が例外を投げた場合は`IN_PROGRESS`予約を解放するが、
 * プロセスがクラッシュした場合（OOM Kill・ノード障害等）は解放処理自体が実行されない。
 * この既知の制約に対し、ADR-0025のOutbox中継が使う「クレーム所有トークン＋タイムアウトベースの
 * 期限切れ判定＋書き戻し時のフェンシング」という3段階Claim方式（同ADR §3）を実装レベルで
 * 適用する。予約行は所有トークン（`claimed_by`）と予約時刻（`claimed_at`）を持ち、
 * 一定時間（既定120秒、`executeLongRunning`の[operation]が「数秒〜数十秒」かかり得ることを
 * 踏まえた値）を超えて`IN_PROGRESS`のままの予約は期限切れとみなされる。
 *
 * Outboxはバックグラウンドポーラーがキューを能動的に排出する必要があるため定期的な
 * タイムアウト監視を持つが、`idempotency_keys`は「同一[idempotencyKey]での再送」が
 * 起きたときにのみ意味を持ち、排出すべきキューが無い。そのためバックグラウンドスイーパーは
 * 持たず、代わりに同一[idempotencyKey]への次回リクエストが期限切れ予約をinlineで安全に
 * 奪取する（trigger機構のみがOutboxと異なり、Claim/Fencingの3段階自体は同型）。
 * 書き戻し（完了記録・解放）時は所有トークンが一致する行にのみ作用するフェンシング条件を
 * 課すため、奪取済みの予約を元の所有者が誤って上書きすることは無い。
 *
 * 実装詳細は`JdbcIdempotentCommandExecutor`（`prompt-engine-infrastructure`、ADR-0027）を参照。
 *
 * **既知の限界（CodeRabbitレビュー指摘、ADR-0027）**: この奪取機構が保証するのは予約の
 * 所有権の一貫性のみであり、奪取された側の[operation]が実際に停止することは保証しない。
 * `claimTimeoutSeconds`はヒューリスティックであるため、元のプロセスが実際には生きていて
 * （GCポーズ・一時的なネットワーク分断等）[operation]の実行を継続している場合、奪取後に
 * 別リクエストが同じ[operation]を再実行すると外部副作用が二重に発生しうる。
 * [executeLongRunning]の[operation]は再実行されても安全（冪等、または呼出先が独自の
 * 冪等キーで重複排除する）であることを呼出側の契約とする。
 */
interface IdempotentCommandExecutor {
    fun <T : Any> executeInTransaction(
        idempotencyKey: String?,
        requestFingerprint: String,
        resultType: Class<T>,
        command: () -> T,
    ): T

    fun <T : Any> executeLongRunning(
        idempotencyKey: String?,
        requestFingerprint: String,
        resultType: Class<T>,
        operation: () -> T,
    ): T
}
