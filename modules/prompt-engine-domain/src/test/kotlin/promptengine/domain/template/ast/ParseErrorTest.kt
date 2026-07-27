package promptengine.domain.template.ast

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class ParseErrorTest {
    @Test
    fun `caretExcerptは該当行と列位置を指すキャレットの2行になる`() {
        val error =
            ParseError(
                kind = ParseErrorKind.SYNTAX_ERROR,
                line = 3,
                column = 5,
                lineText = "{{ if x }}",
                message = "unexpected token",
            )

        error.caretExcerpt() shouldBe "{{ if x }}\n    ^"
    }

    @Test
    fun `line が0以下だとIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> {
            ParseError(ParseErrorKind.SYNTAX_ERROR, line = 0, column = 1, lineText = "", message = "boom")
        }
    }

    @Test
    fun `column が0以下だとIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> {
            ParseError(ParseErrorKind.SYNTAX_ERROR, line = 1, column = 0, lineText = "", message = "boom")
        }
    }

    @Test
    fun `PromptDslParseExceptionのメッセージは種別と位置とキャレット表示を含む`() {
        val error =
            ParseError(
                kind = ParseErrorKind.NESTING_TOO_DEEP,
                line = 2,
                column = 1,
                lineText = "{{#if a}}",
                message = "max nesting depth exceeded",
            )

        val exception = PromptDslParseException(error)
        val message = requireNotNull(exception.message)

        exception.error shouldBe error
        message.shouldContain("NESTING_TOO_DEEP")
        message.shouldContain("line 2, column 1")
        message.shouldContain("^")
    }
}
