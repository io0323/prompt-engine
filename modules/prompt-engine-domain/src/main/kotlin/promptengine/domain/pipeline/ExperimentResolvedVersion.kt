package promptengine.domain.pipeline

import promptengine.domain.prompt.LifecycleState
import promptengine.domain.prompt.PromptVersion
import promptengine.domain.prompt.PromptVersionStateNotAllowedException
import java.util.UUID

/**
 * ADR-0024の状態ゲート（`RENDER_ONLY`/`FULL_EXECUTION`は`Published`/`Deprecated`のみ許可）を
 * 迂回する、Experiment経由の解決専用の値（ADR-0034決定2）。
 *
 * [LoadStage]は[PipelineRequest.preResolvedVersion]が非nullなら、`resolveVersion`/
 * `requireUsableState`（＝ADR-0024のゲート）を一切通さずこの値をそのまま使う。この経路を
 * 無条件に公開すると、Draft状態のPromptVersionをレビュー・承認を経ずに実行できてしまう
 * （P1から積み上げたガバナンスを迂回できてしまう）ため、2段構えで保護する。
 *
 * (a) プライマリコンストラクタを`private`にし、[of]のみが構築できるようにする。
 * (b) [of]自体は`@ExperimentResolutionApi`でオプトインを要求し、ArchUnitルールで
 *     呼出元を`prompt-engine-application`のExperiment解決コンポーネントのみに制限する
 *     （`@PersistenceApi`と同じ「型 + アノテーション + ArchUnit」の三重防御パターン）。
 * (c) [of]自身が[promptVersion]の状態を検証する。**Experiment作成時の検証だけでは
 *     不十分**（実験の実行中に対象Versionが`archive`された場合を作成時検証は捕まえられない）
 *     ため、Variant選択の直後・実行直前というタイミングで毎回再検証する。
 */
class ExperimentResolvedVersion private constructor(
    val promptVersion: PromptVersion,
    val experimentId: UUID,
    val variantId: UUID,
) {
    companion object {
        private val USABLE_STATES = setOf(LifecycleState.Approved, LifecycleState.Published, LifecycleState.Deprecated)

        /**
         * @throws PromptVersionStateNotAllowedException [promptVersion]が`Approved`/
         *   `Published`/`Deprecated`のいずれでもない場合（Draft/InReview/Archived）。
         *   ADR-0024の`LoadStage`が同じ例外を投げるのと同じ理由（「現在の状態では受理できない」）
         *   で同一の例外型・HTTPコード（`VALIDATION_FAILED`）に便乗させる。
         */
        @ExperimentResolutionApi
        fun of(
            promptVersion: PromptVersion,
            experimentId: UUID,
            variantId: UUID,
        ): ExperimentResolvedVersion {
            if (promptVersion.state !in USABLE_STATES) {
                throw PromptVersionStateNotAllowedException(promptVersion.semVer, promptVersion.state)
            }
            return ExperimentResolvedVersion(promptVersion, experimentId, variantId)
        }
    }
}

/**
 * [ExperimentResolvedVersion.of]の呼出元を制限するオプトインアノテーション
 * （`PersistenceApi`と同じ形、ADR-0034決定2）。
 */
@RequiresOptIn(
    message =
        "ExperimentResolvedVersionの構築はExperiment解決専用（ExperimentVariantResolver）。" +
            "通常のPipeline実行経路からは使わないこと。",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
annotation class ExperimentResolutionApi
