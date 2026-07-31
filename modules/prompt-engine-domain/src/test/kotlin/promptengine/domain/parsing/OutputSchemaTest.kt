package promptengine.domain.parsing

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class OutputSchemaTest {
    @Test
    fun `空白のidだとIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> { OutputSchema(id = "  ") }
    }

    @Test
    fun `fieldsは呼出元のMutableListの後続変更から隔離される`() {
        val mutableFields = mutableListOf(OutputSchemaField("answer", OutputFieldType.STRING, required = true))

        val schema = OutputSchema(id = "faq-answer-v1", fields = mutableFields)
        mutableFields.add(OutputSchemaField("extra", OutputFieldType.STRING))

        schema.fields.size shouldBe 1
    }

    @Test
    fun `fields省略時は空リスト`() {
        OutputSchema(id = "empty").fields shouldBe emptyList()
    }
}
