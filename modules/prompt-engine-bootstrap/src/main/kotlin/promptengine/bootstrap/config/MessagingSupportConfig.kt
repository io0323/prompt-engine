package promptengine.bootstrap.config

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import promptengine.infrastructure.masking.SecretMaskingJsonSanitizer
import promptengine.infrastructure.messaging.EventEnvelopeCodec
import promptengine.infrastructure.subscriber.CacheInvalidationPayloadCodec
import promptengine.infrastructure.subscriber.PromptExecutedPayloadCodec

/**
 * 発行側（[OutboxRelayConfig]）と購読側（[SubscriberConfig]）の両方が使う、
 * メッセージ変換・マスクのDI配線（ADR-0026決定1・決定4）。
 *
 * どちらか一方のConfigで定義すると、同じ型のBeanが2つ登録されて型解決が曖昧になるか、
 * 一方のConfigがもう一方に暗黙依存することになるため、共有の置き場としてこのクラスを設ける。
 *
 * `production`プロファイルに限定しない。いずれも純粋な変換ロジックでBrokerへ接続せず、
 * 非productionのテスト（`@SpringBootTest`）からも参照できた方が都合が良いため。
 */
@Configuration
class MessagingSupportConfig {
    /** Brokerメッセージ本文（封筒JSON）のエンコード／デコード。 */
    @Bean
    fun eventEnvelopeCodec(objectMapper: ObjectMapper): EventEnvelopeCodec = EventEnvelopeCodec(objectMapper)

    /** 保存直前のフィールド名ベースSecret redact（マスク第2層）。 */
    @Bean
    fun secretMaskingJsonSanitizer(objectMapper: ObjectMapper): SecretMaskingJsonSanitizer =
        SecretMaskingJsonSanitizer(objectMapper)

    /** `PromptExecuted`のpayload → `PromptExecutionSummary`。 */
    @Bean
    fun promptExecutedPayloadCodec(objectMapper: ObjectMapper): PromptExecutedPayloadCodec =
        PromptExecutedPayloadCodec(objectMapper)

    /** キャッシュ無効化対象イベントのpayload → [promptengine.infrastructure.subscriber.CacheInvalidationTarget]（ADR-0033）。 */
    @Bean
    fun cacheInvalidationPayloadCodec(objectMapper: ObjectMapper): CacheInvalidationPayloadCodec =
        CacheInvalidationPayloadCodec(objectMapper)
}
