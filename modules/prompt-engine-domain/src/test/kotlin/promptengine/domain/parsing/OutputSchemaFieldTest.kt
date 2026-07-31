package promptengine.domain.parsing

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class OutputSchemaFieldTest {
    @Test
    fun `requiredの既定値はfalse`() {
        OutputSchemaField("answer", OutputFieldType.STRING).required shouldBe false
    }

    @Test
    fun `空白のnameだとIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> { OutputSchemaField("  ", OutputFieldType.STRING) }
    }
}
