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
 * **既知の制約（[Issue #50](https://github.com/io0323/prompt-engine/issues/50)）**:
 * [executeLongRunning]は[operation]が例外を投げた場合は`IN_PROGRESS`予約を解放するが、
 * プロセスがクラッシュした場合（OOM Kill・ノード障害等）は解放処理自体が実行されず、
 * 予約が`IN_PROGRESS`のまま残る。この場合、同一[idempotencyKey]への以降の全リクエストが
 * [IdempotencyKeyInProgressException]で永久にブロックされる（手動のDB介入以外に復旧手段が無い）。
 * `IN_PROGRESS`予約に有効期限を持たせ、期限切れ予約を新規リクエストが安全に奪取できるようにする
 * 対応をIssue #50で追跡する（P10）。
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
