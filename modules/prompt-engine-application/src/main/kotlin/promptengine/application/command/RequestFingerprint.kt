package promptengine.application.command

import java.security.MessageDigest

/**
 * `Idempotency-Key`の再送検知（設計書§13.3 `IDEMPOTENCY_KEY_CONFLICT`）用に、リクエスト内容の
 * 正規化済みハッシュを計算する。各Commandの`fingerprintPayload()`（`actor`/`traceId`/
 * `idempotencyKey`を除いた、リクエストボディ相当のフィールドの正規化済み文字列）に対して呼ぶ。
 */
internal fun String.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "") { "%02x".format(it) }
}
