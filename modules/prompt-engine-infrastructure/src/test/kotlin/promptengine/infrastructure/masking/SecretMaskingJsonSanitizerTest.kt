package promptengine.infrastructure.masking

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import promptengine.domain.shared.SensitiveValue

/**
 * Secretマスク第2層（フィールド名ベースのredact、ADR-0026決定4）。
 * `audit_logs.payload`は設計書§12で「Secretマスク済」と定められている。
 */
class SecretMaskingJsonSanitizerTest {
    private val objectMapper = jacksonObjectMapper()
    private val sanitizer = SecretMaskingJsonSanitizer(objectMapper)

    private companion object {
        const val REAL_SECRET = "sk-live-REAL-SECRET-VALUE"
    }

    @Test
    fun `トップレベルのsecret名フィールドはマスクされる`() {
        val result = sanitizer.sanitize("""{"apiSecret":"$REAL_SECRET","promptKey":"support/faq"}""")

        result shouldNotContain REAL_SECRET
        result shouldContain """"apiSecret":"***""""
        result shouldContain """"promptKey":"support/faq""""
    }

    @Test
    fun `入れ子オブジェクトの中もマスクされる`() {
        val result = sanitizer.sanitize("""{"outer":{"inner":{"password":"$REAL_SECRET"}}}""")

        result shouldNotContain REAL_SECRET
        result shouldContain """"password":"***""""
    }

    @Test
    fun `配列要素の中もマスクされる`() {
        val result =
            sanitizer.sanitize("""{"variables":[{"name":"a","token":"$REAL_SECRET"},{"name":"b","value":"ok"}]}""")

        result shouldNotContain REAL_SECRET
        result shouldContain """"value":"ok""""
    }

    @Test
    fun `snake_caseとkebab-caseと大文字の名前も同じ規則で照合される`() {
        val variants =
            listOf("api_key", "API-KEY", "ApiKey", "access_key", "private_key", "credential", "Authorization")

        variants.forEach { field ->
            val result = sanitizer.sanitize("""{"$field":"$REAL_SECRET"}""")

            withCluePrefix(field) { result shouldNotContain REAL_SECRET }
        }
    }

    @Test
    fun `secretを示唆しない名前の値は変更されない`() {
        val input = """{"promptKey":"support/faq","latencyMs":250,"nested":{"retryCount":0}}"""

        objectMapper.readTree(sanitizer.sanitize(input)) shouldBe objectMapper.readTree(input)
    }

    /**
     * `inputTokens`/`outputTokens`/`tokenizerId`は`"token"`を含むが正当なフィールドであり、
     * `PromptExecuted`のpayloadに常に現れる中心的なデータ。部分一致でマスクすると
     * 監査記録が実質的に無意味になるため、後方一致で照合する（実装時にテストで検出した誤検知）。
     */
    @Test
    fun `tokenを含むが正当なフィールドはマスクされない`() {
        val input =
            """
            {"inputTokens":800,"outputTokens":200,"totalTokens":1000,
             "tokenizerId":"approx","maxContextTokens":4096}
            """.trimIndent()

        objectMapper.readTree(sanitizer.sanitize(input)) shouldBe objectMapper.readTree(input)
    }

    @Test
    fun `単数形のtokenフィールドはマスクされる`() {
        val result = sanitizer.sanitize("""{"accessToken":"$REAL_SECRET","token":"$REAL_SECRET"}""")

        result shouldNotContain REAL_SECRET
    }

    @Test
    fun `JSONとして解釈できない入力はそのまま返す`() {
        // サニタイズの失敗が監査記録の欠落を招かないようにする（記録が残らない方が損失が大きい）。
        sanitizer.sanitize("<<<not json>>>") shouldBe "<<<not json>>>"
    }

    @Test
    fun `JSON配列がトップレベルでもマスクされる`() {
        val result = sanitizer.sanitize("""[{"secret":"$REAL_SECRET"},{"ok":1}]""")

        result shouldNotContain REAL_SECRET
    }

    @Test
    fun `nullや数値や真偽値の値を持つフィールドも壊さない`() {
        val input = """{"a":null,"b":1,"c":true,"d":1.5}"""

        objectMapper.readTree(sanitizer.sanitize(input)) shouldBe objectMapper.readTree(input)
    }

    @Test
    fun `secret名フィールドの値がオブジェクトでもまるごとマスクされる`() {
        val result = sanitizer.sanitize("""{"credentials":{"user":"u","pass":"$REAL_SECRET"}}""")

        result shouldNotContain REAL_SECRET
        result shouldContain """"credentials":"***""""
    }

    /**
     * 第1層（[SensitiveValueMaskingModule]）と組み合わせた場合、[SensitiveValue]型の値は
     * フィールド名に関係なく`"***"`になる。
     */
    @Test
    fun `SensitiveValue型の値はフィールド名によらずマスクされる`() {
        val mapper = jacksonObjectMapper().registerModule(SensitiveValueMaskingModule())

        val json = mapper.writeValueAsString(mapOf("harmlessLookingName" to SensitiveValue.of(REAL_SECRET)))

        json shouldNotContain REAL_SECRET
        json shouldBe """{"harmlessLookingName":"***"}"""
    }

    private fun withCluePrefix(
        field: String,
        block: () -> Unit,
    ) = io.kotest.assertions.withClue("field=$field") { block() }

    // ---- sanitizeFreeText（ADR-0027決定3、CodeRabbitレビュー指摘: message/exceptionの自由記述対策） ----

    @Test
    fun `key=valueで秘密を示唆するkeyの値はマスクされる`() {
        val result = sanitizer.sanitizeFreeText("token=$REAL_SECRET")

        result shouldNotContain REAL_SECRET
        result shouldBe "token=***"
    }

    @Test
    fun `logger呼出しが生成するtoken=形式のmessageもマスクされる`() {
        // logger.info("token={}", secret) がSLF4Jのプレースホルダ置換後に生成する文字列そのもの。
        val formattedMessage = "calling provider with token=$REAL_SECRET status=pending"

        val result = sanitizer.sanitizeFreeText(formattedMessage)

        result shouldNotContain REAL_SECRET
        result shouldContain "token=***"
        result shouldContain "status=pending"
    }

    @Test
    fun `例外メッセージに含まれる秘密もマスクされる`() {
        val exceptionText = "java.lang.IllegalStateException: connection failed password=$REAL_SECRET\n\tat ..."

        val result = sanitizer.sanitizeFreeText(exceptionText)

        result shouldNotContain REAL_SECRET
        result shouldContain "password=***"
    }

    @Test
    fun `コロン区切りのkey_valueは意図的に非対応であり値は変更されない`() {
        // sanitizeFreeTextのKDoc「原理的な限界」参照。コロンは自由記述テキストの中では
        // key=valueの合図として曖昧すぎる（下の回帰テストが実例を示す）ため対象外とする。
        val result = sanitizer.sanitizeFreeText("""apiKey: $REAL_SECRET""")

        result shouldContain REAL_SECRET
    }

    @Test
    fun `例外のClassName直後にkey=value形式の秘密が続いても正しく検出される`() {
        // 実際にCI環境で再現したバグの回帰テスト: コロンをkey=value区切りとして扱うと
        // "IllegalStateException:"をキー、直後の"apiKey=secret"全体を値として1マッチに
        // 貪欲に取り込んでしまい（"IllegalStateException"はSecretを示唆しないため
        // マスク対象外と判定され）、本来検出すべきapiKey=secret自体が素通りしていた。
        val exceptionToString = "java.lang.IllegalStateException: apiKey=$REAL_SECRET rejected"

        val result = sanitizer.sanitizeFreeText(exceptionToString)

        result shouldNotContain REAL_SECRET
        result shouldContain "apiKey=***"
    }

    @Test
    fun `inputTokens outputTokensのkey=value形式はマスクされない`() {
        val text = "usage: inputTokens=120 outputTokens=30 latencyMs=42"

        val result = sanitizer.sanitizeFreeText(text)

        result shouldBe text
    }

    @Test
    fun `key value対応の無い自由記述文はマスクできない既知の限界`() {
        // sanitizeFreeTextのKDocに明記された原理的限界。key=value/key: value形状が無いプレーンな
        // 文中の秘密はどの層でも検出できない（SensitiveValue型でのラップのみが完全な保証）。
        val text = "the secret is $REAL_SECRET"

        val result = sanitizer.sanitizeFreeText(text)

        result shouldContain REAL_SECRET
    }
}
