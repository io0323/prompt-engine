package promptengine.domain.template.ast

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ExpressionTest {
    @Test
    fun `同一構造のExpressionは構造的に等価である`() {
        val a =
            Expression(
                operand = PropertyRef(listOf("context", "application", "serviceName")),
                filters = listOf(FilterCall("upper"), FilterCall("truncate", listOf(NumberLiteral(100.0)))),
            )
        val b =
            Expression(
                operand = PropertyRef(listOf("context", "application", "serviceName")),
                filters = listOf(FilterCall("upper"), FilterCall("truncate", listOf(NumberLiteral(100.0)))),
            )

        a shouldBe b
    }

    @Test
    fun `PropertyRefのpathが空だとIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> {
            PropertyRef(emptyList())
        }
    }

    @Test
    fun `PropertyRefのpathに空文字セグメントを含むとIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> {
            PropertyRef(listOf("context", ""))
        }
    }

    @Test
    fun `FilterCallの名前が空文字だとIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> {
            FilterCall("")
        }
    }

    @Test
    fun `リテラル被演算子は種別ごとに値を保持する`() {
        StringLiteral("polite").value shouldBe "polite"
        NumberLiteral(100.0).value shouldBe 100.0
        BooleanLiteral(true).value shouldBe true
    }
}
