package promptengine.application.pipeline

import promptengine.domain.optimization.ModelProfile
import promptengine.domain.pipeline.PipelineMode
import promptengine.domain.pipeline.PipelineRequest
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.VersionRef
import promptengine.domain.shared.PromptRequest
import promptengine.domain.shared.SemVer
import promptengine.domain.shared.TokenCount

/**
 * `SubmitReviewHandler`の「Validation合格」ガード評価を切り出した協調オブジェクト
 * （detektの`LongParameterList`閾値対策で[PipelineOrchestrator]・[ModelProfile]の2つを
 * まとめる。`CommandHandlersConfig`のKDoc「detekt閾値対策で分割」と同じ考え方）。
 *
 * [PipelineOrchestrator]をCOMPILE_ONLYモード（設計書§2.6「1〜3+6」）で呼ぶ。
 * `ValidationStage`はERROR severityの`Finding`が1件でもあれば自ら`ValidationFailedException`
 * を投げる（ADR-0015決定4）ため、[assertValidationPassed]が例外を投げずに戻れば
 * Validation合格が確定している。
 */
class ReviewValidationGate(
    private val pipelineOrchestrator: PipelineOrchestrator,
    private val modelProfile: ModelProfile,
) {
    fun assertValidationPassed(
        key: PromptKey,
        semVer: SemVer,
        traceId: String,
    ) {
        val request =
            PipelineRequest(
                promptKey = key,
                versionRef = VersionRef.Fixed(semVer),
                variableResolution = PromptRequest(),
                modelProfile = modelProfile,
                // COMPILE_ONLYはStage 7 Optimizationまで到達しないため値自体は使われない。
                budget = TokenCount(UNUSED_COMPILE_ONLY_BUDGET),
            )
        pipelineOrchestrator.run(request, PipelineMode.COMPILE_ONLY, traceId)
    }

    private companion object {
        const val UNUSED_COMPILE_ONLY_BUDGET = 0
    }
}
