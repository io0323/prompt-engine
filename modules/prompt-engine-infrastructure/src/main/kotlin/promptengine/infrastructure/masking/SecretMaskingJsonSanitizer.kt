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

    /**
     * JSONツリー構造を持たない自由記述テキスト（ログの`message`/`exception`等）向けの
     * サニタイズ（`SanitizingJsonEncoder`、ADR-0027決定3のCodeRabbitレビュー指摘）。
     *
     * [sanitize]はJSONオブジェクトのフィールド**名**でしか照合できないため、
     * `logger.info("token={}", secret)`のような呼出しが生成する`message: "token=sk-..."`は
     * 素通りしていた（フィールドではなく1つの自由記述文字列の中身であるため）。
     * このメソッドは`key=value`形状を正規表現で検出し、[isSensitiveName]と同じ判定で
     * key部分がSecretを示唆する場合のみvalueを[SensitiveValueMaskingModule.MASK]へ置換する。
     *
     * `key: value`（コロン区切り）は**意図的に非対応**とする。例外の文字列表現
     * （`Throwable.toString()`）は`"ClassName: message"`という、コロンの直後に
     * スペースを挟む形式であり、`message`が`apiKey=secret`のように始まる場合
     * `ClassName:`をキー、`\s*`直後の`apiKey=secret`全体を値として1つの
     * （マスク対象外と判定される）マッチに貪欲に取り込んでしまい、本来別マッチとして
     * 検出すべき`apiKey=secret`自体を隠してしまう（実装時に発覚した実バグ。CI環境で
     * `IllegalStateException("apiKey=...")`のメッセージがマスクされずに再現した）。
     * スタックトレースの`at Foo.bar(File.kt:20)`のような行にもコロンが多用されており、
     * コロンは`key: value`の合図として自由記述テキストの中では曖昧すぎる。
     * `=`はこの曖昧さが無いため、`=`のみをサニタイズ対象の区切り文字とする。
     *
     * 原理的な限界: `key`と`value`の対応が構文的に明示されない自由記述
     * （例: `"the secret is sk-live-xyz"`）は検出できない。これはSecretマスクの第1層
     * （[SensitiveValueMaskingModule]、[promptengine.domain.shared.SensitiveValue]型経由の
     * 値は`toString()`が常に`"***"`を返す）でのみ完全に防げる。本メソッドは
     * `key=value`という一般的な慣用形にのみ対応する第3層の追加防御であり、
     * 万能の代替ではない。
     */
    fun sanitizeFreeText(text: String): String =
        KEY_VALUE_PATTERN.replace(text) { match ->
            val (key, separator, _) = match.destructured
            if (isSensitiveName(key)) "$key$separator${SensitiveValueMaskingModule.MASK}" else match.value
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

        /**
         * `key=value`形状の自由記述テキストにマッチする（`:`区切りを対象外とする理由は
         * [sanitizeFreeText]のKDoc参照）。値は引用符付き（内部の`\"`エスケープを許容）か、
         * 空白・カンマ・セミコロンまでの非空白トークンのいずれか。
         */
        val KEY_VALUE_PATTERN = Regex("""([\w.-]+)(=)\s*("(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'|[^\s,;]+)""")
    }
}
