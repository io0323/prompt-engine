package promptengine.infrastructure.masking

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * 任意のJSON payloadから、名前がSecretを示唆するフィールドの値を`"***"`へ置き換える
 * （ADR-0026決定4、Secretマスクの第2層）。
 *
 * 第1層（[SensitiveValueMaskingModule]）は「[promptengine.domain.shared.SensitiveValue]型で
 * 表現された値」を型レベルで確実にマスクする。しかしそれは、Secretが常にその型を経由して
 * payloadへ入ることを前提にしている。`AuditEngine`は設計書§14の全6トピック・全イベント種別
 * （まだ具象クラスが存在しないものを含む）を`audit_logs`へ保存するため、将来
 * 型を経由せず生の`String`としてSecretが混ざるイベントが追加される可能性を構造的に排除できない。
 *
 * 本サニタイザはその残余リスクに対する多層防御として、保存直前にフィールド**名**ベースで
 * redactする。名前ベースの照合は原理的に不完全（[SENSITIVE_NAME_FRAGMENTS]に無い名前は
 * 素通りする）だが、第1層と組み合わせることで「型を通った秘密」と
 * 「典型的な名前の秘密」の両方を塞ぐ。
 *
 * JSONとして解釈できない入力はそのまま返す（サニタイズの失敗が監査記録の欠落を
 * 引き起こさないようにするため。監査記録が残らないことの方が運用上の損失が大きい）。
 */
class SecretMaskingJsonSanitizer(
    private val objectMapper: ObjectMapper,
) {
    /** [json]をサニタイズしたJSON文字列を返す。 */
    fun sanitize(json: String): String {
        val root = runCatching { objectMapper.readTree(json) }.getOrNull() ?: return json
        return objectMapper.writeValueAsString(redact(root))
    }

    private fun redact(node: JsonNode): JsonNode =
        when (node) {
            is ObjectNode -> redactObject(node)
            is ArrayNode -> redactArray(node)
            else -> node
        }

    private fun redactObject(node: ObjectNode): ObjectNode {
        val result = objectMapper.createObjectNode()
        node.fields().forEach { (name, value) ->
            if (isSensitiveName(name)) {
                result.put(name, SensitiveValueMaskingModule.MASK)
            } else {
                result.set<JsonNode>(name, redact(value))
            }
        }
        return result
    }

    private fun redactArray(node: ArrayNode): ArrayNode {
        val result = objectMapper.createArrayNode()
        node.forEach { element -> result.add(redact(element)) }
        return result
    }

    private fun isSensitiveName(name: String): Boolean {
        val normalized = name.lowercase().replace("_", "").replace("-", "")
        return SENSITIVE_NAME_SUFFIXES.any { normalized.endsWith(it) }
    }

    private companion object {
        /**
         * フィールド名（小文字化し`_`/`-`を除去したもの）がこれらのいずれかで**終わる**場合、
         * 値をマスクする。
         *
         * 部分一致（`contains`）ではなく後方一致にしているのは、`inputTokens`/`outputTokens`/
         * `totalTokens`/`tokenizerId`のような正当なフィールドが`"token"`を含むために
         * マスクされてしまうため。これらは`PromptExecuted`のpayloadに常に現れる中心的な
         * データであり、マスクしてしまうと監査記録・DLQ退避が実質的に無意味になる
         * （実装時にテストで検出した誤検知。単数形の`token`だけが後方一致し、複数形の
         * `...Tokens`は一致しない）。
         *
         * 複数形が実際に使われうる語（`credentials`等）は明示的に列挙する。
         */
        val SENSITIVE_NAME_SUFFIXES =
            listOf(
                "secret",
                "secrets",
                "password",
                "passwd",
                "token",
                "apikey",
                "accesskey",
                "privatekey",
                "credential",
                "credentials",
                "authorization",
            )
    }
}
