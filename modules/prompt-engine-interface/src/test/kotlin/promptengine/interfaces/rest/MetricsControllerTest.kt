package promptengine.interfaces.rest

import io.kotest.assertions.throwables.shouldThrow
import io.mockk.mockk
import org.junit.jupiter.api.Test
import promptengine.application.query.MetricsHandler
import java.time.Instant

/**
 * `MetricsController.summarize`のfrom/to境界検証（CodeRabbitレビュー指摘: from>toだと
 * `JdbcMetricsRepository`が`BETWEEN`をそのまま発行し0件集計を返してしまう）。
 *
 * `prompt-engine-interface`は`promptengine.domain..`型に依存できない（ArchUnitルール）ため、
 * `MetricsHandler.handle`が返す`MetricsSummary`（domain型）をこのモジュールのテストコードで
 * 構築できない。境界検証自体は`handler.handle`を呼ぶ前に完了するため、正常系（実際に
 * ハンドラへ委譲されるパス）の検証はここでは行わない。
 */
class MetricsControllerTest {
    private val handler = mockk<MetricsHandler>()
    private val controller = MetricsController(handler)

    @Test
    fun `fromがtoより後ならIllegalArgumentExceptionを投げハンドラは呼ばれない`() {
        val from = Instant.parse("2026-01-02T00:00:00Z")
        val to = Instant.parse("2026-01-01T00:00:00Z")

        shouldThrow<IllegalArgumentException> {
            controller.summarize("support", "faq-answer", from, to)
        }
    }
}
