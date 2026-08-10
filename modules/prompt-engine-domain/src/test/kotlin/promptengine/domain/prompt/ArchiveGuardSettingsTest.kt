package promptengine.domain.prompt

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class ArchiveGuardSettingsTest {
    private val cutoverAt = Instant.parse("2026-08-01T00:00:00Z")

    @Test
    fun `無活動判定窓の既定値は90日`() {
        ArchiveGuardSettings(cutoverAt).inactivityThreshold shouldBe Duration.ofDays(90)
    }

    @Test
    fun `無活動判定窓が0の場合は例外を投げる`() {
        shouldThrow<IllegalArgumentException> { ArchiveGuardSettings(cutoverAt, Duration.ZERO) }
    }

    @Test
    fun `無活動判定窓が負の場合は例外を投げる`() {
        shouldThrow<IllegalArgumentException> { ArchiveGuardSettings(cutoverAt, Duration.ofDays(-1)) }
    }

    @Test
    fun `カットオーバー時刻と判定窓を明示指定できる`() {
        val settings = ArchiveGuardSettings(cutoverAt, Duration.ofDays(30))

        settings.executionLogsCutoverAt shouldBe cutoverAt
        settings.inactivityThreshold shouldBe Duration.ofDays(30)
    }
}
