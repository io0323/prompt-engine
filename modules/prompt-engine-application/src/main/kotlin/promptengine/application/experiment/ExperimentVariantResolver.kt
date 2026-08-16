package promptengine.application.experiment

import promptengine.domain.experiment.ExperimentRepository
import promptengine.domain.experiment.TrafficSplitStrategy
import promptengine.domain.pipeline.ExperimentResolutionApi
import promptengine.domain.pipeline.ExperimentResolvedVersion
import promptengine.domain.pipeline.PipelineRequest
import promptengine.domain.prompt.PromptRepository

/**
 * Pipeline実行前にExperiment割当を解決するApplication層コンポーネント（ADR-0034決定1・2）。
 *
 * `RenderUseCase`/`ExecuteUseCase`が`PipelineOrchestrator.run()`を呼ぶ**前**に[resolve]を
 * 呼ぶ。対象PromptKeyに`Running`中のExperimentが無ければ[request]をそのまま返す
 * （Experiment非対象の通常経路、`LoadStage`が既存の`resolveVersion`/ADR-0024ゲートで
 * 解決する）。
 *
 * `Running`中のExperimentが1件でもあれば、[TrafficSplitStrategy]でVariantを選び、
 * そのVariantが参照する`PromptVersion`を[promptRepository]から直接取得したうえで
 * [ExperimentResolvedVersion.of]（状態再検証込み、ADR-0034決定2b）を通し、
 * `request.preResolvedVersion`へ設定した[PipelineRequest]を返す。
 *
 * **例外を握り潰さない**: [ExperimentResolvedVersion.of]が
 * [promptengine.domain.prompt.PromptVersionStateNotAllowedException]を投げた場合
 * （対象Versionが実験実行中にarchiveされた等）、ここで捕捉して通常解決へ静かに
 * フォールバックしない。「どのVariantが使われたか分からない実行」を防ぐため、
 * 呼出元（UseCase）へエラーとしてそのまま伝播させる（ADR-0034決定2）。
 *
 * 同一PromptKeyに`Running`中のExperimentが複数存在する状態は不変条件として発生しない
 * （Experiment開始コマンドハンドラが同一PromptKeyでの重複開始を拒否する）ため、
 * [ExperimentRepository.findActiveByPrompt]の結果が複数件であっても先頭の1件を使う。
 */
class ExperimentVariantResolver(
    private val experimentRepository: ExperimentRepository,
    private val promptRepository: PromptRepository,
    private val trafficSplitStrategy: TrafficSplitStrategy,
) {
    @OptIn(ExperimentResolutionApi::class)
    fun resolve(request: PipelineRequest): PipelineRequest {
        val experiment = experimentRepository.findActiveByPrompt(request.promptKey).firstOrNull() ?: return request

        val stickyValue =
            experiment.trafficPolicy.stickyKeyPath?.let { path ->
                readStickyValue(request.variableResolution.contextData, path)
            }
        val variant = trafficSplitStrategy.select(experiment, stickyValue)

        val prompt =
            promptRepository.findByKey(request.promptKey)
                ?: error("Prompt not found for active experiment: '${request.promptKey.value}'")
        val promptVersion =
            prompt.versions.find { it.semVer == variant.promptVersionSemVer }
                ?: error(
                    "Experiment variant '${variant.name}' references PromptVersion " +
                        "${variant.promptVersionSemVer} which no longer exists on Prompt '${request.promptKey.value}'",
                )

        val resolved = ExperimentResolvedVersion.of(promptVersion, experiment.experimentId, variant.variantId)
        return request.copy(preResolvedVersion = resolved)
    }

    /**
     * [path]は`"scope.key"`形式（例 `"user.id"`、ADR-0034決定3）。先頭セグメントを
     * [promptengine.domain.shared.PromptRequest.contextData]のスコープキー、残りを
     * スコープ内のキーとして扱う。
     */
    private fun readStickyValue(
        contextData: Map<String, Map<String, Any>>,
        path: String,
    ): String? {
        val segments = path.split(".", limit = 2)
        if (segments.size < 2) return null
        val (scope, key) = segments
        return contextData[scope]?.get(key)?.toString()
    }
}
