package promptengine.infrastructure.logging

import ch.qos.logback.classic.pattern.ThrowableProxyConverter
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.encoder.EncoderBase
import com.fasterxml.jackson.databind.ObjectMapper
import promptengine.infrastructure.masking.SecretMaskingJsonSanitizer
import java.nio.charset.StandardCharsets
import java.time.Instant

/**
 * 構造化ログ（JSON）のEncoder（設計書§2.15 Logging、ADR-0027決定3）。
 *
 * ログ出力の**唯一の**Encoder（`logback-spring.xml`が全プロファイル共通で束線する）として、
 * フォーマット済みJSONを書き出す直前に必ず[SecretMaskingJsonSanitizer]を通す。
 * `logger.info(...)`等の呼出側は一切関与できない単一入口であり、P7の`RenderHashCalculator`と
 * 同じ「呼び忘れようがない」構造をログ出力へ適用したもの（P4/P6/P7で積んできた
 * 「経路別テスト」による呼出規約ではなく、Encoder層という構造で秘密マスクを担保する、
 * ADR-0027決定3）。
 *
 * [SensitiveValue][promptengine.domain.shared.SensitiveValue]型を経由する値は
 * `toString()`が既に`"***"`を返す（第1層防御）。本Encoderの[SecretMaskingJsonSanitizer]は
 * フィールド**名**ベースの第2層防御であり、型を経由せず生の文字列としてメッセージへ
 * 混入した秘密（例: `logger.info("token={}", secret)`のような誤用）もマスクする
 * （`AuditEngine`が`audit_logs`書き込み前に使う多層防御と同じ設計思想をログ出口全体へ広げる）。
 *
 * MDC（`traceId`/`promptKey`/`version`、設計書§2.15「相関ID」）をそのままJSONフィールドへ
 * 展開する。[TraceIdFilter][promptengine.interfaces.support.TraceIdFilter]・
 * `PipelineOrchestrator`がMDCへの投入/除去を担い、本Encoderは受け取ったMDCをそのまま
 * 出力するのみ（ログ出力経路とMDC投入経路を分離する）。
 */
class SanitizingJsonEncoder : EncoderBase<ILoggingEvent>() {
    private val objectMapper = ObjectMapper()
    private val sanitizer = SecretMaskingJsonSanitizer(objectMapper)
    private val throwableConverter = ThrowableProxyConverter()

    override fun start() {
        throwableConverter.start()
        super.start()
    }

    override fun stop() {
        throwableConverter.stop()
        super.stop()
    }

    override fun headerBytes(): ByteArray? = null

    override fun footerBytes(): ByteArray? = null

    override fun encode(event: ILoggingEvent): ByteArray {
        val fields = LinkedHashMap<String, Any?>()
        fields["timestamp"] = Instant.ofEpochMilli(event.timeStamp).toString()
        fields["level"] = event.level.toString()
        fields["logger"] = event.loggerName
        fields["thread"] = event.threadName
        fields["message"] = event.formattedMessage
        if (event.mdcPropertyMap.isNotEmpty()) {
            fields.putAll(event.mdcPropertyMap)
        }
        if (event.throwableProxy != null) {
            fields["exception"] = throwableConverter.convert(event)
        }
        val rawJson = objectMapper.writeValueAsString(fields)
        val sanitizedJson = sanitizer.sanitize(rawJson)
        return (sanitizedJson + System.lineSeparator()).toByteArray(StandardCharsets.UTF_8)
    }
}
