package promptengine.application.pipeline

import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.audit.AuditFailureHandler
import promptengine.domain.audit.AuditOutcome
import promptengine.domain.audit.AuditRecord
import promptengine.domain.audit.AuditRepository
import promptengine.domain.execution.ExecutionPolicy
import promptengine.domain.optimization.ModelProfile
import promptengine.domain.pipeline.PipelineContext
import promptengine.domain.pipeline.PipelineMode
import promptengine.domain.pipeline.PipelineRequest
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.VersionRef
import promptengine.domain.shared.Cost
import promptengine.domain.shared.PromptRequest
import promptengine.domain.shared.TokenCount
import java.math.BigDecimal

/** [AuditStage]の単体テスト（設計書§2.6ステージ12、ADR-0015決定7・決定8）。 */
class AuditStageTest {
    private val modelProfile =
        ModelProfile(
            maxContextTokens = TokenCount(1_000),
            tokenizerId = "test-tokenizer",
            costPerToken = Cost(BigDecimal.ZERO),
        )
    private val context =
        PipelineContext(
            request =
                PipelineRequest(
                    promptKey = PromptKey("support/faq"),
                    versionRef = VersionRef.Latest,
                    variableResolution = PromptRequest(),
                    modelProfile = modelProfile,
                    budget = TokenCount(1_000),
                    executionPolicy = ExecutionPolicy(timeoutMs = 1_000),
                ),
            mode = PipelineMode.FULL_EXECUTION,
            traceId = "trace-audit-stage",
            stageDurationsMs = mapOf("Load" to 1L),
        )

    private class RecordingAuditRepository : AuditRepository {
        val records = mutableListOf<AuditRecord>()

        override fun append(record: AuditRecord) {
            records += record
        }
    }

    private class ThrowingAuditRepository : AuditRepository {
        override fun append(record: AuditRecord): Nothing = error("audit store unavailable (test)")
    }

    private class RecordingAuditFailureHandler : AuditFailureHandler {
        val handled = mutableListOf<Pair<AuditRecord, Throwable>>()

        override fun handle(
            record: AuditRecord,
            cause: Throwable,
        ) {
            handled += record to cause
        }
    }

    @Test
    fun `execute は traceId promptKey mode stageDurationsMsをそのままSuccessとして記録する`() {
        val repository = RecordingAuditRepository()
        val stage = AuditStage(repository, RecordingAuditFailureHandler())

        val result = stage.execute(context)

        result shouldBe context
        val record = repository.records.single()
        record.traceId shouldBe "trace-audit-stage"
        record.promptKey shouldBe "support/faq"
        record.mode shouldBe PipelineMode.FULL_EXECUTION
        record.stageDurationsMs shouldBe mapOf("Load" to 1L)
        record.outcome shouldBe AuditOutcome.Success
    }

    @Test
    fun `永続化されるAuditRecordはAudit自身のdurationを含まない 構造的な制約 AuditStageのKDoc参照`() {
        // AuditRecordはexecute内でappend(永続化)される時点で作られるため、
        // Audit自身のdurationはまだ確定していない（PipelineOrchestratorが
        // execute()の戻り値を受け取った後に初めて計測が完了する）。
        // AuditRepositoryは追記専用で更新を提供しないため、永続化後に
        // "Audit"キーを追記する経路は存在しない（CodeRabbitレビュー指摘対応）。
        val repository = RecordingAuditRepository()
        val stage = AuditStage(repository, RecordingAuditFailureHandler())

        stage.execute(context)

        repository.records.single().stageDurationsMs.keys shouldNotContain "Audit"
    }

    @Test
    fun `recordFailure はerrorCodeを持つFailureとして記録する`() {
        val repository = RecordingAuditRepository()
        val stage = AuditStage(repository, RecordingAuditFailureHandler())

        stage.recordFailure(context, StageErrorMapper.VALIDATION_FAILED)

        repository.records.single().outcome shouldBe AuditOutcome.Failure(StageErrorMapper.VALIDATION_FAILED)
    }

    @Test
    fun `append失敗時は例外を伝播させずAuditFailureHandlerへ委譲する`() {
        val failureHandler = RecordingAuditFailureHandler()
        val stage = AuditStage(ThrowingAuditRepository(), failureHandler)

        stage.execute(context)

        failureHandler.handled.size shouldBe 1
        failureHandler.handled.single().first.traceId shouldBe "trace-audit-stage"
    }

    @Test
    fun `isAuditable はFULL_EXECUTIONのみtrue`() {
        AuditStage.isAuditable(PipelineMode.FULL_EXECUTION) shouldBe true
        AuditStage.isAuditable(PipelineMode.RENDER_ONLY) shouldBe false
        AuditStage.isAuditable(PipelineMode.COMPILE_ONLY) shouldBe false
    }
}
