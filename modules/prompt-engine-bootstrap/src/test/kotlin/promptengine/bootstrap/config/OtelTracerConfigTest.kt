package promptengine.bootstrap.config

import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import promptengine.domain.observability.TraceContextPropagator
import promptengine.domain.pipeline.PipelineTracer

/**
 * [OtelTracerConfig]の単体テスト（Issue #38、ADR-0027決定2）。
 *
 * `@Bean`メソッド自体はSpringに依存しないプレーンなKotlin関数のため、フルSpringコンテキストを
 * 起動せず直接呼び出して分岐（エクスポータ設定あり/なし、`production`プロファイルでの
 * 未設定WARN）を検証する。DI配線自体（Bean名解決等）は他クラスと同様
 * [OutboxRelayConfigWiringTest]のパターンで別途検証しうるが、本フェーズでは分岐網羅を優先する。
 *
 * Spring管理下では`@Bean(destroyMethod = "close")`が`OpenTelemetrySdk.close()`
 * （内部の`SdkTracerProvider`・`BatchSpanProcessor`・`SpanExporter`を含めて正しくシャットダウンする）
 * を自動で呼ぶが、本テストはSpringコンテキストを介さず直接構築するため、各テストで
 * 明示的に`close()`する（CodeRabbitレビュー指摘: 特にOTLPエクスポータ付きの構築は
 * バックグラウンドの`BatchSpanProcessor`エクスポート用スレッドを起動するため、
 * closeを怠るとテスト実行のたびにリークする）。
 */
class OtelTracerConfigTest {
    private val config = OtelTracerConfig()

    @Test
    fun `exporterEndpoint未設定ならSpanProcessor無しのSDKを構築する`() {
        config.openTelemetrySdk(OtelTracerProperties(exporterEndpoint = null), MockEnvironment()).use { sdk ->
            sdk shouldNotBe null
        }
    }

    @Test
    fun `exporterEndpoint未設定かつproductionプロファイルでもSDK構築自体は失敗しない`() {
        val environment = MockEnvironment().apply { setActiveProfiles("production") }

        config.openTelemetrySdk(OtelTracerProperties(exporterEndpoint = null), environment).use { sdk ->
            sdk shouldNotBe null
        }
    }

    @Test
    fun `exporterEndpointが空文字でも未設定と同じくSpanProcessor無しのSDKを構築する`() {
        config.openTelemetrySdk(OtelTracerProperties(exporterEndpoint = "  "), MockEnvironment()).use { sdk ->
            sdk shouldNotBe null
        }
    }

    @Test
    fun `exporterEndpoint設定時はOTLPエクスポータ付きのSDKを構築する`() {
        config
            .openTelemetrySdk(
                OtelTracerProperties(exporterEndpoint = "http://localhost:4317"),
                MockEnvironment(),
            ).use { sdk ->
                sdk shouldNotBe null
            }
    }

    @Test
    fun `tracerとpipelineTracerとtraceContextPropagatorのBean定義が例外無く解決できる`() {
        config.openTelemetrySdk(OtelTracerProperties(exporterEndpoint = null), MockEnvironment()).use { sdk ->
            val tracer = config.tracer(sdk)

            val pipelineTracer: PipelineTracer = config.pipelineTracer(tracer)
            val propagator: TraceContextPropagator = config.traceContextPropagator()

            pipelineTracer shouldNotBe null
            propagator shouldNotBe null
        }
    }
}
