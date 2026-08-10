package promptengine.bootstrap.config

import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import promptengine.domain.observability.TraceContextPropagator
import promptengine.domain.pipeline.PipelineTracer
import promptengine.infrastructure.observability.OpenTelemetryPipelineTracer
import promptengine.infrastructure.observability.OpenTelemetryTraceContextPropagator

private const val PRODUCTION_PROFILE = "production"

/**
 * §2.15のTracing配線（Issue #38、ADR-0027決定2）。
 *
 * `OutboxRelayConfig`（[promptengine.bootstrap.config.OutboxRelayConfig]）とは異なり
 * `@Profile("production")`を付けない。[PipelineOrchestrator][promptengine.application.pipeline.PipelineOrchestrator]
 * は全プロファイルで`PipelineTracer` Beanを要求するため、このConfiguration自体は常に
 * 有効化し、エクスポータを繋ぐかどうかだけを[OtelTracerProperties.exporterEndpoint]の
 * 有無で切り替える。
 *
 * [OtelTracerProperties.exporterEndpoint]が空/未設定の場合、[SdkTracerProvider]に
 * `SpanProcessor`を一切登録しない。Spanオブジェクト自体は生成されるが、処理系が無いため
 * 即座に破棄されるだけであり、ネットワーク呼出も起動遅延も発生しない
 * （`OpenTelemetry.noop()`へ切り替える分岐を作るより、常に同じ`SdkTracerProvider`の
 * コードパスを通す方がテスト対象を環境間で揃えられる）。`production`プロファイルで
 * 未設定の場合は起動時にWARNログを出す（Trace欠落は診断能力の低下であり、`AuditRepository`
 * 等のコンプライアンス要件（NFR-006）とは重みが異なるため、起動失敗にはしない、ADR-0027決定2）。
 */
@Configuration
@EnableConfigurationProperties(OtelTracerProperties::class)
class OtelTracerConfig {
    @Bean(destroyMethod = "close")
    fun openTelemetrySdk(
        properties: OtelTracerProperties,
        environment: Environment,
    ): OpenTelemetrySdk {
        val endpoint = properties.exporterEndpoint
        val tracerProviderBuilder = SdkTracerProvider.builder()
        if (endpoint.isNullOrBlank()) {
            if (PRODUCTION_PROFILE in environment.activeProfiles) {
                logger.warn(
                    "otel_exporter_endpoint_unset profile={} : " +
                        "promptengine.observability.otel.exporter-endpoint is not set; " +
                        "spans will be created but never exported (diagnostic-only, not a startup failure)",
                    PRODUCTION_PROFILE,
                )
            }
        } else {
            val exporter = OtlpGrpcSpanExporter.builder().setEndpoint(endpoint).build()
            tracerProviderBuilder.addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
        }
        return OpenTelemetrySdk.builder().setTracerProvider(tracerProviderBuilder.build()).build()
    }

    @Bean
    fun tracer(openTelemetrySdk: OpenTelemetrySdk): Tracer = openTelemetrySdk.getTracer("promptengine")

    @Bean
    fun pipelineTracer(tracer: Tracer): PipelineTracer = OpenTelemetryPipelineTracer(tracer)

    /**
     * M2の`ApapExecutionAdapter`が使う想定の`traceparent`生成（Issue #38、ADR-0027決定2）。
     * 現行M1には出力先の実HTTP呼出経路が無いため配線先は無いが、契約自体は先に用意する
     * （[TraceContextPropagator]のKDoc参照）。
     */
    @Bean
    fun traceContextPropagator(): TraceContextPropagator = OpenTelemetryTraceContextPropagator()

    private companion object {
        val logger = LoggerFactory.getLogger(OtelTracerConfig::class.java)
    }
}
