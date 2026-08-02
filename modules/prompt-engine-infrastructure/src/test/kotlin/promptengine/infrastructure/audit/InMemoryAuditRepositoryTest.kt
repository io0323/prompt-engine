package promptengine.infrastructure.audit

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.audit.AuditLogEntry
import promptengine.domain.audit.AuditOutcome
import promptengine.domain.audit.AuditQuery
import promptengine.domain.audit.AuditRecord
import promptengine.domain.pipeline.PipelineMode
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** [InMemoryAuditRepository]の単体テスト（ADR-0015決定7）。 */
class InMemoryAuditRepositoryTest {
    private fun record(traceId: String) =
        AuditRecord(
            traceId = traceId,
            promptKey = "support/faq",
            mode = PipelineMode.FULL_EXECUTION,
            stageDurationsMs = emptyMap(),
            outcome = AuditOutcome.Success,
            occurredAt = Instant.EPOCH,
        )

    @Test
    fun `production プロファイルでは構築時にIllegalStateExceptionを投げる`() {
        shouldThrow<IllegalStateException> { InMemoryAuditRepository(setOf("production")) }
    }

    @Test
    fun `production 以外のプロファイルでは構築でき appendした記録をsnapshotで取得できる`() {
        val repository = InMemoryAuditRepository(setOf("local"))

        repository.append(record("trace-1"))
        repository.append(record("trace-2"))

        repository.snapshot().map { it.traceId } shouldBe listOf("trace-1", "trace-2")
    }

    @Test
    fun `appendと並行してsnapshotを呼んでもConcurrentModificationExceptionを起こさない`() {
        // Collections.synchronizedListのtoList()はモニタで保護されないイテレーションを行うため、
        // 他スレッドの並行書き込みと衝突しうる（CodeRabbitレビュー指摘）。synchronized(records)で
        // イテレーション全体を囲むことで、多数の並行書き込み下でもsnapshotが例外を起こさないことを
        // ストレス的に検証する。
        val repository = InMemoryAuditRepository(setOf("local"))
        val writerCount = 8
        val writesPerWriter = 500
        val executor = Executors.newFixedThreadPool(writerCount + 1)
        val readyLatch = CountDownLatch(writerCount + 1)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(writerCount + 1)
        val failures = mutableListOf<Throwable>()

        repeat(writerCount) { writerIndex ->
            executor.submit {
                readyLatch.countDown()
                startLatch.await()
                try {
                    repeat(writesPerWriter) { i -> repository.append(record("writer-$writerIndex-$i")) }
                } catch (t: Throwable) {
                    synchronized(failures) { failures += t }
                } finally {
                    doneLatch.countDown()
                }
            }
        }
        executor.submit {
            readyLatch.countDown()
            startLatch.await()
            try {
                repeat(writesPerWriter) { repository.snapshot() }
            } catch (t: Throwable) {
                synchronized(failures) { failures += t }
            } finally {
                doneLatch.countDown()
            }
        }

        readyLatch.await()
        startLatch.countDown()
        doneLatch.await(30, TimeUnit.SECONDS)
        executor.shutdown()

        failures shouldBe emptyList()
    }

    private fun logEntry(
        aggregateId: String,
        actor: String,
        occurredAt: Instant,
    ) = AuditLogEntry(
        auditId = UUID.randomUUID(),
        aggregateType = "Prompt",
        aggregateId = aggregateId,
        action = "Published",
        actor = actor,
        payload = "{}",
        traceId = "trace-$aggregateId",
        occurredAt = occurredAt,
    )

    @Test
    fun `recordした行はsearchで新しい順に返る`() {
        val repository = InMemoryAuditRepository(setOf("local"))
        repository.record(logEntry("support/faq", "user:a", Instant.ofEpochSecond(1)))
        repository.record(logEntry("support/faq", "user:b", Instant.ofEpochSecond(2)))

        val page = repository.search(AuditQuery())

        page.items.map { it.actor } shouldBe listOf("user:b", "user:a")
        page.totalElements shouldBe 2L
    }

    @Test
    fun `searchはaggregateId actor from to で絞り込める`() {
        val repository = InMemoryAuditRepository(setOf("local"))
        repository.record(logEntry("support/faq", "user:a", Instant.ofEpochSecond(1)))
        repository.record(logEntry("support/other", "user:a", Instant.ofEpochSecond(2)))
        repository.record(logEntry("support/faq", "user:b", Instant.ofEpochSecond(3)))

        repository.search(AuditQuery(aggregateId = "support/faq")).items.map { it.actor } shouldBe
            listOf("user:b", "user:a")
        repository.search(AuditQuery(actor = "user:a")).totalElements shouldBe 2L
        repository.search(AuditQuery(from = Instant.ofEpochSecond(2))).totalElements shouldBe 2L
        repository.search(AuditQuery(to = Instant.ofEpochSecond(1))).totalElements shouldBe 1L
    }

    @Test
    fun `page が巨大でもオーバーフローせず空ページを返す`() {
        // page * sizeをIntのまま計算するとInt.MAX_VALUE付近でオーバーフローし、
        // 負のfromIndexによりIndexOutOfBoundsException等を起こしうる（CodeRabbitレビュー指摘）。
        val repository = InMemoryAuditRepository(setOf("local"))
        repository.record(logEntry("support/faq", "user:a", Instant.ofEpochSecond(1)))

        val page = repository.search(AuditQuery(page = Int.MAX_VALUE / 10, size = 20))

        page.items shouldBe emptyList()
        page.totalElements shouldBe 1L
    }

    @Test
    fun `searchはpage size でページングする`() {
        val repository = InMemoryAuditRepository(setOf("local"))
        repeat(5) { i -> repository.record(logEntry("support/faq", "user:$i", Instant.ofEpochSecond(i.toLong()))) }

        val page = repository.search(AuditQuery(page = 1, size = 2))

        page.items.size shouldBe 2
        page.totalElements shouldBe 5L
        page.page shouldBe 1
        page.size shouldBe 2
    }
}
