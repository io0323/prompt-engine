package promptengine.bootstrap.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * OTLPエクスポータの送信先設定（`promptengine.observability.otel.*`、ADR-0027決定2）。
 *
 * [exporterEndpoint]が空/未設定なら[OtelTracerConfig]はSpanを生成しても一切エクスポートしない
 * （ネットワーク呼出・起動遅延が発生しない、ローカル・CIでの既定挙動）。
 */
@ConfigurationProperties(prefix = "promptengine.observability.otel")
data class OtelTracerProperties(
    /** OTLP gRPCエクスポータの送信先（例: `http://otel-collector:4317`）。未設定ならエクスポートしない。 */
    val exporterEndpoint: String? = null,
)
