package promptengine.infrastructure.logging

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.nio.charset.StandardCharsets

private const val SECRET_VALUE = "sk-live-do-not-leak-98765"

/**
 * [SanitizingJsonEncoder]の単体テスト（設計書§2.15、ADR-0027決定3）。
 *
 * 本Encoderが組み立てるJSONの構造化フィールドは`timestamp`/`level`/`logger`/`thread`/
 * `message`/MDCエントリ/`exception`のみであり、`message`/`exception`はSLF4Jが組み立てた
 * 自由記述文字列（JSONツリーとしては単一の文字列値）である。[SecretMaskingJsonSanitizer]
 * （P10b）はJSONオブジェクトの**フィールド名**で照合するため、実際にマスク対象になり得るのは
 * MDCエントリ（`traceId`/`promptKey`等、キー名を持つ構造化フィールド）である。
 * このテストはP10bの`inputTokens`過剰マスク回帰と同じ観点で、両方向
 * （秘密が出ない・秘密でない値が壊れない）を検証する。
 */
class SanitizingJsonEncoderTest {
    private lateinit var appender: ListAppender<ILoggingEvent>
    private lateinit var logger: Logger
    private lateinit var encoder: SanitizingJsonEncoder

    @BeforeEach
    fun setUp() {
        logger = LoggerFactory.getLogger(SanitizingJsonEncoderTest::class.java) as Logger
        appender = ListAppender()
        appender.start()
        logger.addAppender(appender)
        encoder = SanitizingJsonEncoder()
        encoder.start()
    }

    @AfterEach
    fun tearDown() {
        logger.detachAppender(appender)
        encoder.stop()
        MDC.clear()
    }

    private fun encodeSingleEvent(): String = String(encoder.encode(appender.list.single()), StandardCharsets.UTF_8)

    @Test
    fun `MDCのapiKeyフィールドはマスクされる`() {
        MDC.put("apiKey", SECRET_VALUE)
        try {
            logger.info("calling external provider")
        } finally {
            MDC.remove("apiKey")
        }

        val json = encodeSingleEvent()

        json shouldNotContain SECRET_VALUE
        json shouldContain "\"apiKey\":\"***\""
    }

    @Test
    fun `traceIdとpromptKeyのMDCはマスクされずそのまま出力される`() {
        MDC.put("traceId", "trace-abc")
        MDC.put("promptKey", "support/faq")
        try {
            logger.info("pipeline stage completed")
        } finally {
            MDC.remove("traceId")
            MDC.remove("promptKey")
        }

        val json = encodeSingleEvent()

        json shouldContain "\"traceId\":\"trace-abc\""
        json shouldContain "\"promptKey\":\"support/faq\""
    }

    @Test
    fun `inputTokens outputTokensのMDCはtokenで終わる名前と紛らわしいがマスクされない`() {
        // P10bで実際に踏んだ誤検知（SecretMaskingJsonSanitizerのKDoc参照）と同じ観点の回帰テスト。
        MDC.put("inputTokens", "120")
        MDC.put("outputTokens", "30")
        try {
            logger.info("execution usage recorded")
        } finally {
            MDC.remove("inputTokens")
            MDC.remove("outputTokens")
        }

        val json = encodeSingleEvent()

        json shouldContain "\"inputTokens\":\"120\""
        json shouldContain "\"outputTokens\":\"30\""
    }

    @Test
    fun `messageとlevelとloggerが出力される`() {
        logger.info("hello world")

        val json = encodeSingleEvent()

        json shouldContain "\"message\":\"hello world\""
        json shouldContain "\"level\":\"INFO\""
        json shouldContain "\"logger\":\"promptengine.infrastructure.logging.SanitizingJsonEncoderTest\""
    }

    @Test
    fun `headerBytesとfooterBytesは常にnull`() {
        // JSON Lines形式（1行1レコード）のため、Appender全体を包むヘッダ・フッタは不要。
        encoder.headerBytes() shouldBe null
        encoder.footerBytes() shouldBe null
    }

    @Test
    fun `例外のスタックトレースがexceptionフィールドへ載る`() {
        logger.error("failed", IllegalStateException("boom"))

        val json = encodeSingleEvent()

        json shouldContain "IllegalStateException"
        json shouldContain "boom"
    }
}
