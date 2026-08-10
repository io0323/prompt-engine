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
}
