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
 * `message`/MDCエントリ/`exception`。MDCエントリは[SecretMaskingJsonSanitizer.sanitize]の
 * フィールド**名**ベース照合でマスクされ、`message`/`exception`（自由記述の1文字列）は
 * [SecretMaskingJsonSanitizer.sanitizeFreeText]の`key=value`形状照合でマスクされる
 * （後者はCodeRabbitレビュー指摘: `logger.info("token={}", secret)`のような呼出しが
 * 生成する`message`はフィールド名ベースの照合だけでは素通りしていた）。
 * このテストはP10bの`inputTokens`過剰マスク回帰と同じ観点で、両方向
 * （秘密が出ない・秘密でない値が壊れない）をMDC・message・exceptionそれぞれについて検証する。
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

    @Test
    fun `logger呼出しがtoken=形式のmessageへ秘密を埋め込んでもマスクされる`() {
        // logger.info("token={}", secret) はSLF4Jのプレースホルダ置換により
        // formattedMessage = "token=<secret>" を生成する。フィールド名を持たないこの1文字列は
        // SecretMaskingJsonSanitizer.sanitizeだけでは素通りしていた（CodeRabbitレビュー指摘）。
        logger.info("token={}", SECRET_VALUE)

        val json = encodeSingleEvent()

        json shouldNotContain SECRET_VALUE
        json shouldContain "token=***"
    }

    @Test
    fun `例外メッセージに含まれる秘密もmessage同様にマスクされる`() {
        logger.error("call failed", IllegalStateException("apiKey=$SECRET_VALUE rejected"))

        val json = encodeSingleEvent()

        json shouldNotContain SECRET_VALUE
        json shouldContain "apiKey=***"
    }
}
