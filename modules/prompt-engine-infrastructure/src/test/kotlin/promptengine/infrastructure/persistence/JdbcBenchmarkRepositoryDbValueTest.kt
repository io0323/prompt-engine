package promptengine.infrastructure.persistence

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.benchmark.BenchmarkStatus

/**
 * [BenchmarkStatus.toDbValue]/[benchmarkStatusFromDbValue]のテスト
 * （`LifecycleState.toDbValue`/`lifecycleStateFromDbValue`と同じ、DB文字列⇔domain型の
 * 相互変換テスト。Testcontainers不要の純粋関数）。
 */
class JdbcBenchmarkRepositoryDbValueTest {
    @Test
    fun `toDbValue は全状態を対応する文字列へ変換する`() {
        BenchmarkStatus.Pending.toDbValue() shouldBe "Pending"
        BenchmarkStatus.Running.toDbValue() shouldBe "Running"
        BenchmarkStatus.Cancelling.toDbValue() shouldBe "Cancelling"
        BenchmarkStatus.Completed.toDbValue() shouldBe "Completed"
        BenchmarkStatus.Cancelled.toDbValue() shouldBe "Cancelled"
        BenchmarkStatus.Failed.toDbValue() shouldBe "Failed"
    }

    @Test
    fun `benchmarkStatusFromDbValue は全文字列を対応する状態へ変換する`() {
        benchmarkStatusFromDbValue("Pending") shouldBe BenchmarkStatus.Pending
        benchmarkStatusFromDbValue("Running") shouldBe BenchmarkStatus.Running
        benchmarkStatusFromDbValue("Cancelling") shouldBe BenchmarkStatus.Cancelling
        benchmarkStatusFromDbValue("Completed") shouldBe BenchmarkStatus.Completed
        benchmarkStatusFromDbValue("Cancelled") shouldBe BenchmarkStatus.Cancelled
        benchmarkStatusFromDbValue("Failed") shouldBe BenchmarkStatus.Failed
    }

    @Test
    fun `benchmarkStatusFromDbValue は未知の文字列でIllegalStateExceptionを投げる`() {
        shouldThrow<IllegalStateException> { benchmarkStatusFromDbValue("Unknown") }
    }
}
