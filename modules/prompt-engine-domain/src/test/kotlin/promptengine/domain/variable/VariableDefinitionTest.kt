package promptengine.domain.variable

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class VariableDefinitionTest {
    @Test
    fun `name type required default constraints sensitive を指定して生成できる`() {
        val definition =
            VariableDefinition(
                name = "userName",
                type = VariableType.STRING,
                required = true,
                default = null,
                constraints = listOf("maxLength:64"),
                sensitive = false,
            )

        definition.name shouldBe "userName"
        definition.type shouldBe VariableType.STRING
        definition.required shouldBe true
        definition.constraints shouldBe listOf("maxLength:64")
        definition.sensitive shouldBe false
    }

    @Test
    fun `required default sensitive constraints は省略時にデフォルト値を持つ`() {
        val definition = VariableDefinition(name = "userName", type = VariableType.STRING)

        definition.required shouldBe false
        definition.default shouldBe null
        definition.constraints shouldBe emptyList()
        definition.sensitive shouldBe false
    }

    @Test
    fun `name が空文字だとIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> {
            VariableDefinition(name = "", type = VariableType.STRING)
        }
    }

    @Test
    fun `sensitive=trueかつdefault指定ありだとIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> {
            VariableDefinition(name = "apiKeyRef", type = VariableType.STRING, sensitive = true, default = "leaked")
        }
    }

    @Test
    fun `sensitive=trueでもdefaultがnullなら生成できる`() {
        val definition =
            VariableDefinition(name = "apiKeyRef", type = VariableType.STRING, sensitive = true, default = null)

        definition.sensitive shouldBe true
        definition.default shouldBe null
    }

    @Test
    fun `source は省略時にSTATICになる`() {
        val definition = VariableDefinition(name = "tone", type = VariableType.STRING)

        definition.source shouldBe VariableSource.STATIC
    }

    @Test
    fun `source=SECRETかつsensitive=falseだとIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> {
            VariableDefinition(
                name = "apiKeyRef",
                type = VariableType.STRING,
                source = VariableSource.SECRET,
                sensitive = false,
            )
        }
    }

    @Test
    fun `source=SECRETかつsensitive=trueなら生成できる`() {
        val definition =
            VariableDefinition(
                name = "apiKeyRef",
                type = VariableType.STRING,
                source = VariableSource.SECRET,
                required = true,
                sensitive = true,
            )

        definition.source shouldBe VariableSource.SECRET
        definition.sensitive shouldBe true
    }
}
