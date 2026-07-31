package promptengine.domain.parsing

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.render.OutputFormat

class ParsedOutputTest {
    @Test
    fun `fieldsは呼出元のMutableMapの後続変更から隔離される`() {
        val mutableFields = mutableMapOf<String, Any?>("answer" to "42")

        val output = ParsedOutput(OutputFormat.JSON, fields = mutableFields, raw = """{"answer":"42"}""")
        mutableFields["extra"] = "leaked"

        output.fields shouldBe mapOf("answer" to "42")
    }

    @Test
    fun `fields省略時は空マップ`() {
        ParsedOutput(OutputFormat.TEXT, raw = "hello").fields shouldBe emptyMap()
    }
}
