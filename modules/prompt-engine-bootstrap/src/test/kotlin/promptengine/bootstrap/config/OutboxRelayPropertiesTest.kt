package promptengine.bootstrap.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/** [OutboxRelayProperties]の値検証（CodeRabbitレビュー指摘）の単体テスト。 */
class OutboxRelayPropertiesTest {
    @Test
    fun `既定値はすべて正の値`() {
        val properties = OutboxRelayProperties()
        properties.pollIntervalMs shouldBe 750L
        properties.batchSize shouldBe 50
        properties.claimTimeoutSeconds shouldBe 30L
    }

    @Test
    fun `batchSizeが0以下だと構築時に例外`() {
        val exception = shouldThrow<IllegalArgumentException> { OutboxRelayProperties(batchSize = 0) }
        exception.message shouldContain "batch-size"
    }

    @Test
    fun `claimTimeoutSecondsが0以下だと構築時に例外`() {
        val exception = shouldThrow<IllegalArgumentException> { OutboxRelayProperties(claimTimeoutSeconds = -1) }
        exception.message shouldContain "claim-timeout-seconds"
    }

    @Test
    fun `pollIntervalMsが0以下だと構築時に例外`() {
        val exception = shouldThrow<IllegalArgumentException> { OutboxRelayProperties(pollIntervalMs = 0) }
        exception.message shouldContain "poll-interval-ms"
    }
}
