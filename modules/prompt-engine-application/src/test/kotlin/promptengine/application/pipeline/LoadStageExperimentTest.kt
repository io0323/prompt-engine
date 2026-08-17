package promptengine.application.pipeline

import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test
import promptengine.domain.event.EventContext
import promptengine.domain.optimization.ModelProfile
import promptengine.domain.pipeline.ExperimentResolutionApi
import promptengine.domain.pipeline.ExperimentResolvedVersion
import promptengine.domain.pipeline.PipelineContext
import promptengine.domain.pipeline.PipelineMode
import promptengine.domain.pipeline.PipelineRequest
import promptengine.domain.prompt.NewPromptVersion
import promptengine.domain.prompt.Prompt
import promptengine.domain.prompt.PromptAliasRepository
import promptengine.domain.prompt.PromptContent
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.PromptRepository
import promptengine.domain.prompt.VersionRef
import promptengine.domain.shared.Cost
import promptengine.domain.shared.PromptRequest
import promptengine.domain.shared.SemVer
import promptengine.domain.shared.TokenCount
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * [LoadStage]の`preResolvedVersion`分岐（ADR-0034決定2）を検証する。
 * 状態ゲート（ADR-0024）を含む通常解決分岐は[PipelineStageGuardsTest]が担う。
 */
@OptIn(ExperimentResolutionApi::class)
class LoadStageExperimentTest {
    private val modelProfile = ModelProfile(TokenCount(1_000), "tokenizer", Cost(BigDecimal.ZERO))
    private val eventContext =
        EventContext(actor = "user:owner", traceId = "trace-1", occurredAt = Instant.parse("2026-01-01T00:00:00Z"))

    @Test
    fun `preResolvedVersionが設定されていれば通常解決を経由せずpromptVersionとexperimentVariantIdを設定する`() {
        val key = PromptKey("support/experiment-prompt")
        val semVer = SemVer(1, 0, 0)
        val prompt =
            Prompt.create(key, NewPromptVersion(semVer, PromptContent("hello")), eventContext).first
                .submitForReview(semVer, validationPassed = true)
                .approve(semVer, approvalCount = 1, requiredApprovalCount = 1)
        val approvedVersion = prompt.versions.single { it.semVer == semVer }
        val experimentId = UUID.randomUUID()
        val variantId = UUID.randomUUID()
        val resolved = ExperimentResolvedVersion.of(approvedVersion, experimentId, variantId)

        // promptRepository/aliasRepositoryはpreResolvedVersion経路では一切呼ばれないことを
        // mockkの未スタブ呼び出し検出（未設定メソッド呼び出しでMockKException）で確認する。
        val promptRepository = mockk<PromptRepository>()
        val aliasRepository = mockk<PromptAliasRepository>()
        val stage = LoadStage(promptRepository, aliasRepository)
        val request =
            PipelineRequest(
                promptKey = key,
                versionRef = VersionRef.Latest,
                variableResolution = PromptRequest(),
                modelProfile = modelProfile,
                budget = TokenCount(1_000),
                preResolvedVersion = resolved,
            )

        val result = stage.execute(PipelineContext(request, PipelineMode.FULL_EXECUTION, "trace-1"))

        result.promptVersion shouldBe approvedVersion
        result.experimentVariantId shouldBe variantId
    }
}
