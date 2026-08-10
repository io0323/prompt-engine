package promptengine.bootstrap.config

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import promptengine.domain.observability.MetricsRecorder
import promptengine.infrastructure.observability.MicrometerMetricsRecorder

/**
 * §2.15のMetrics配線（ADR-0027決定2）。
 *
 * `spring-boot-starter-actuator`が[MeterRegistry]を自動構成する（`micrometer-registry-prometheus`が
 * クラスパスにあれば`PrometheusMeterRegistry`、無ければ既定の`SimpleMeterRegistry`）ため、
 * このConfigurationはPush型のエクスポータ設定を持たない。`/actuator/prometheus`が
 * （`management.endpoints.web.exposure.include`設定に従い）Prometheusのスクレイプ対象を
 * 公開する。ローカル・CIでの起動失敗やテスト遅延のリスクが無い（`OtelTracerConfig`の
 * Push型OTLPエクスポータとは対照的、ADR-0027決定2）。
 */
@Configuration
class MetricsConfig {
    @Bean
    fun metricsRecorder(meterRegistry: MeterRegistry): MetricsRecorder = MicrometerMetricsRecorder(meterRegistry)
}
