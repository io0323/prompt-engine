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
 * `toString()`が既に`"***"`を返す（第1層防御）。[SecretMaskingJsonSanitizer.sanitize]は
 * MDCエントリ等JSONオブジェクトの構造化フィールド**名**ベースの第2層防御である。
 * `message`/`exception`は自由記述の1文字列であり、フィールド名ベースの照合が及ばない
 * （`logger.info("token={}", secret)`のような呼出しが生成する`message: "token=sk-..."`は
 * [SecretMaskingJsonSanitizer.sanitize]だけでは素通りする、CodeRabbitレビュー指摘）ため、
 * 第3層として[SecretMaskingJsonSanitizer.sanitizeFreeText]を`message`/`exception`へ個別に
 * 適用してから残りのフィールドと合わせてJSON化・構造的サニタイズする。
 * `key=value`という構文的な対応が無い自由記述（例:「秘密の値はsk-live-xyzです」）は
 * この第3層でも検出できない、[SecretMaskingJsonSanitizer.sanitizeFreeText]のKDoc参照。
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
        fields["message"] = sanitizer.sanitizeFreeText(event.formattedMessage)
        if (event.mdcPropertyMap.isNotEmpty()) {
            fields.putAll(event.mdcPropertyMap)
        }
        if (event.throwableProxy != null) {
            fields["exception"] = sanitizer.sanitizeFreeText(throwableConverter.convert(event))
        }
        val rawJson = objectMapper.writeValueAsString(fields)
        val sanitizedJson = sanitizer.sanitize(rawJson)
        return (sanitizedJson + System.lineSeparator()).toByteArray(StandardCharsets.UTF_8)
    }
}
