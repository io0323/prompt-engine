package promptengine.plugin.formatter.json

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import promptengine.domain.parsing.OutputFieldType
import promptengine.domain.parsing.OutputSchema
import promptengine.domain.parsing.OutputSchemaField
import promptengine.domain.parsing.ParseFailedException
import promptengine.domain.render.OutputFormat

class JsonOutputFormatterTest {
    private val formatter = JsonOutputFormatter()
    private val schema =
        OutputSchema(
            id = "faq-answer-v1",
            fields =
                listOf(
                    OutputSchemaField("answer", OutputFieldType.STRING, required = true),
                    OutputSchemaField("confidence", OutputFieldType.NUMBER),
                ),
        )

    @Test
    fun `formatはJSON`() {
        formatter.format() shouldBe OutputFormat.JSON
    }

    @Test
    fun `instructionはschemaが無くてもJSON限定の指示文を返す`() {
        formatter.instruction(null) shouldContain "JSON"
    }

    @Test
    fun `instructionはschemaのフィールド名を含む`() {
        formatter.instruction(schema) shouldContain "answer"
    }

    @Test
    fun `正常なJSONをparseできる`() {
        val result = formatter.parse("""{"answer":"42","confidence":0.9}""", schema)

        result.format shouldBe OutputFormat.JSON
        result.fields["answer"] shouldBe "42"
        result.fields["confidence"] shouldBe 0.9
    }

    @Test
    fun `コードフェンス付きJSONを剥がしてparseできる`() {
        val fenced = "```json\n{\"answer\":\"42\"}\n```"

        val result = formatter.parse(fenced, null)

        result.fields["answer"] shouldBe "42"
    }

    @Test
    fun `必須フィールドが欠けているとPARSE_FAILEDを投げる`() {
        val exception = shouldThrow<ParseFailedException> { formatter.parse("""{"confidence":0.5}""", schema) }

        exception.reason shouldBe "missing required field: answer"
    }

    @Test
    fun `型が一致しないとPARSE_FAILEDを投げる`() {
        val exception =
            shouldThrow<ParseFailedException> { formatter.parse("""{"answer":123}""", schema) }

        exception.reason shouldContain "answer"
        exception.reason shouldContain "STRING"
    }

    @Test
    fun `構文的に不正なJSONはinvalid JSON syntaxという固定理由を返しJacksonの詳細を含まない`() {
        val exception = shouldThrow<ParseFailedException> { formatter.parse("{not valid json", null) }

        exception.reason shouldBe "invalid JSON syntax"
    }

    @Test
    fun `トップレベルが配列だとPARSE_FAILEDを投げる`() {
        val exception = shouldThrow<ParseFailedException> { formatter.parse("[1,2,3]", null) }

        exception.reason shouldBe "top-level JSON value must be an object"
    }

    // ---- 秘密情報漏洩経路: 不正JSON中の秘密情報マーカーが例外メッセージに出ないこと（ADR-0014決定9） ----

    @Test
    fun `漏洩経路 構文エラー時の例外メッセージに入力中の秘密情報マーカーが含まれない`() {
        val secretMarker = "sk-real-secret-marker"

        val exception = shouldThrow<ParseFailedException> { formatter.parse("{broken-$secretMarker", null) }

        exception.message.shouldNotContain(secretMarker)
        exception.reason.shouldNotContain(secretMarker)
    }

    @Test
    fun `漏洩経路 型不一致時の例外メッセージに値そのものは含まれずフィールド名のみを含む`() {
        val secretValue = "sk-real-secret-value"

        val exception =
            shouldThrow<ParseFailedException> {
                formatter.parse("""{"answer":123,"note":"$secretValue"}""", schema)
            }

        exception.message.shouldNotContain(secretValue)
    }

    @Test
    fun `fieldsは全てのJSON値型をKotlin値へ変換する`() {
        val raw =
            """
            {
              "str": "text",
              "intNum": 42,
              "doubleNum": 3.5,
              "flag": true,
              "list": [1, "two", true],
              "obj": {"nested": "value"},
              "nothing": null
            }
            """.trimIndent()

        val result = formatter.parse(raw, null)

        result.fields["str"] shouldBe "text"
        result.fields["intNum"] shouldBe 42L
        result.fields["doubleNum"] shouldBe 3.5
        result.fields["flag"] shouldBe true
        result.fields["list"] shouldBe listOf(1L, "two", true)
        result.fields["obj"] shouldBe mapOf("nested" to "value")
        result.fields["nothing"] shouldBe null
    }

    @Test
    fun `BOOLEAN ARRAY OBJECTの型検証を通過できる`() {
        val typedSchema =
            OutputSchema(
                id = "typed",
                fields =
                    listOf(
                        OutputSchemaField("flag", OutputFieldType.BOOLEAN, required = true),
                        OutputSchemaField("list", OutputFieldType.ARRAY, required = true),
                        OutputSchemaField("obj", OutputFieldType.OBJECT, required = true),
                    ),
            )

        val result = formatter.parse("""{"flag":true,"list":[1,2],"obj":{"a":1}}""", typedSchema)

        result.fields["flag"] shouldBe true
    }

    @Test
    fun `BOOLEANフィールドの型不一致を検出する`() {
        val typedSchema =
            OutputSchema(id = "x", fields = listOf(OutputSchemaField("flag", OutputFieldType.BOOLEAN, required = true)))

        val exception = shouldThrow<ParseFailedException> { formatter.parse("""{"flag":"not-bool"}""", typedSchema) }

        exception.reason shouldContain "flag"
    }

    @Test
    fun `ARRAYフィールドの型不一致を検出する`() {
        val typedSchema =
            OutputSchema(id = "x", fields = listOf(OutputSchemaField("list", OutputFieldType.ARRAY, required = true)))

        val exception = shouldThrow<ParseFailedException> { formatter.parse("""{"list":"not-array"}""", typedSchema) }

        exception.reason shouldContain "list"
    }

    @Test
    fun `OBJECTフィールドの型不一致を検出する`() {
        val typedSchema =
            OutputSchema(id = "x", fields = listOf(OutputSchemaField("obj", OutputFieldType.OBJECT, required = true)))

        val exception = shouldThrow<ParseFailedException> { formatter.parse("""{"obj":[1,2]}""", typedSchema) }

        exception.reason shouldContain "obj"
    }

    @Test
    fun `NUMBERフィールドの型不一致を検出する`() {
        val typedSchema =
            OutputSchema(id = "x", fields = listOf(OutputSchemaField("n", OutputFieldType.NUMBER, required = true)))

        val exception = shouldThrow<ParseFailedException> { formatter.parse("""{"n":"not-number"}""", typedSchema) }

        exception.reason shouldContain "n"
    }

    @Test
    fun `instructionはfieldsが空のschemaならフィールド一覧を含まない`() {
        val emptySchema = OutputSchema(id = "empty-schema")

        val instruction = formatter.instruction(emptySchema)

        instruction shouldContain "JSON"
        instruction.shouldNotContain("empty-schema")
    }

    @Test
    fun `jsonラベル無しのコードフェンスも剥がせる`() {
        val fenced = "```\n{\"answer\":\"42\"}\n```"

        val result = formatter.parse(fenced, null)

        result.fields["answer"] shouldBe "42"
    }

    @Test
    fun `任意のフィールドにnull値を明示した場合も許容フィールドの欠落とはみなさない`() {
        val result = formatter.parse("""{"answer":"42","confidence":null}""", schema)

        result.fields["confidence"] shouldBe null
    }
}
