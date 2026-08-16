package promptengine.application.pipeline

import promptengine.domain.pipeline.PipelineContext
import promptengine.domain.pipeline.PipelineMode
import promptengine.domain.pipeline.PipelineStage
import promptengine.domain.prompt.LifecycleState
import promptengine.domain.prompt.PromptAliasRepository
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.PromptRepository
import promptengine.domain.prompt.PromptVersion
import promptengine.domain.prompt.PromptVersionNotFoundException
import promptengine.domain.prompt.PromptVersionStateNotAllowedException
import promptengine.domain.prompt.VersionRef

/**
 * Stage 1（Load、設計書§2.6）。[PromptRepository]から`request.promptKey`/`versionRef`に
 * 対応する[PromptVersion]を読み込む（実装ガイド§6.9「既存のEngineに委譲するだけの薄い層」）。
 *
 * P8時点ではキャッシュを持たない（ADR-0015決定11。Issue #15解消後に別PRで追加）ため、
 * 毎回[promptRepository]へ直接問い合わせる。
 *
 * [VersionRef.Alias]の解決（別名→実Version）は[aliasRepository]（`PromptAliasRepository`、
 * `prompt_aliases`テーブル、設計書§12）で行う。当初実装ガイド§6.9のStage 1は`VersionRef`3種
 * すべてへの対応を前提としていたが、Alias永続化の経路が無いまま常に未解決扱いしていた
 * 欠落をP8完了の一部として解消する。Alias自体が見つからない場合、Alias解決先のVersionが
 * 存在しない場合のいずれも[PromptVersionNotFoundException]を投げる（`PROMPT_NOT_FOUND`
 * として扱う。呼出元はこの2状態を区別しない）。
 *
 * [VersionRef.Latest]の解決は`versions.find { Published }`ではなく[resolveLatestPublished]で
 * 明示的に0件/1件/2件以上を場合分けする（CodeRabbitレビュー指摘への対応。当初
 * `singleOrNull { Published }`を使う案を検討したが、Kotlin標準ライブラリの
 * `singleOrNull(predicate)`は複数件マッチ時に例外ではなく`null`を返す仕様であり、
 * `?: throw PromptVersionNotFoundException`と組み合わせると「不変条件が壊れて
 * Publishedが2件以上ある」状態と「Publishedが0件」状態の両方が同じ
 * `PROMPT_NOT_FOUND`として静かに丸められてしまい、狙いだった「壊れたら落ちる」
 * 効果が得られていなかった）。`Prompt.init`が「Publishedは同時に1件まで」という
 * 不変条件を保証しているため`.find`でも実害は無いという指摘への反論自体は正しいが、
 * 将来この不変条件が変更・破られた場合に静かに1件目を返す経路を無くすため、
 * 2件以上のケースを`IllegalStateException`で明示的に区別する。
 *
 * **状態ゲート（ADR-0024）**: `VersionRef.Fixed`/`VersionRef.Alias`は指定/解決されたSemVerの
 * Versionをその状態を問わず素通しで返す実装だったため、`RENDER_ONLY`/`FULL_EXECUTION`から
 * Draft/InReview/Approved状態のVersionを直接参照できてしまっていた（9c、E2E確認で発覚）。
 * P3c `CompositionService`が確立した「Draft相互参照はCOMPILE_ONLYでのみ許可」というルール
 * （[promptengine.domain.composition.DraftReferenceNotAllowedException]、ADR-0009/0012）を、
 * Compositionが解決する依存だけでなく主`PromptVersion`自体の解決にも適用し、
 * `COMPILE_ONLY`以外では`Published`/`Deprecated`のみ許可する
 * （[PromptVersionStateNotAllowedException]、`VersionRef.Latest`は元々Publishedのみを返すため
 * 影響なし）。
 *
 * **Experiment経由の解決（ADR-0034決定2）**: `context.request.preResolvedVersion`が
 * 非nullの場合、上記の状態ゲートを含む通常解決を一切行わずその値をそのまま使う。
 * `ExperimentResolvedVersion`は`ExperimentVariantResolver`（`prompt-engine-application`）
 * のみが構築でき、構築時点で状態が再検証済み（`Approved`/`Published`/`Deprecated`）である
 * ため、ここで改めて`requireUsableState`を通す必要はない。
 */
class LoadStage(
    private val promptRepository: PromptRepository,
    private val aliasRepository: PromptAliasRepository,
) : PipelineStage {
    override val name: String = "Load"

    override fun execute(context: PipelineContext): PipelineContext {
        val preResolved = context.request.preResolvedVersion
        if (preResolved != null) {
            return context.copy(
                promptVersion = preResolved.promptVersion,
                experimentVariantId = preResolved.variantId,
            )
        }

        val key = context.request.promptKey
        val prompt = promptRepository.findByKey(key) ?: throw PromptVersionNotFoundException.forKey(key)

        val version = resolveVersion(prompt.versions, context.request.versionRef, key, context.mode)
        return context.copy(promptVersion = version)
    }

    private fun resolveVersion(
        versions: List<PromptVersion>,
        versionRef: VersionRef,
        key: PromptKey,
        mode: PipelineMode,
    ): PromptVersion {
        val version =
            when (versionRef) {
                is VersionRef.Fixed -> resolveFixed(versions, versionRef)
                is VersionRef.Latest -> resolveLatestPublished(versions, key)
                is VersionRef.Alias -> resolveAlias(versions, key, versionRef)
            }
        return requireUsableState(version, mode)
    }

    private fun resolveFixed(
        versions: List<PromptVersion>,
        versionRef: VersionRef.Fixed,
    ): PromptVersion =
        versions.find { it.semVer == versionRef.semVer }
            ?: throw PromptVersionNotFoundException(versionRef.semVer)

    private fun resolveAlias(
        versions: List<PromptVersion>,
        key: PromptKey,
        versionRef: VersionRef.Alias,
    ): PromptVersion {
        val target =
            aliasRepository.find(key, versionRef.name)?.semVer
                ?: throw PromptVersionNotFoundException.forKey(key)
        return versions.find { it.semVer == target } ?: throw PromptVersionNotFoundException(target)
    }

    private fun requireUsableState(
        version: PromptVersion,
        mode: PipelineMode,
    ): PromptVersion {
        if (mode == PipelineMode.COMPILE_ONLY) return version
        if (version.state != LifecycleState.Published && version.state != LifecycleState.Deprecated) {
            throw PromptVersionStateNotAllowedException(version.semVer, version.state)
        }
        return version
    }

    /**
     * `Prompt.init`の「Publishedは同時に1件まで」不変条件が壊れた場合に静かに1件目を
     * 返さず、2件以上を明示的に検出して`IllegalStateException`で落とす（CodeRabbitレビュー
     * 指摘、[LoadStage]のKDoc参照）。
     */
    private fun resolveLatestPublished(
        versions: List<PromptVersion>,
        key: PromptKey,
    ): PromptVersion {
        val published = versions.filter { it.state == LifecycleState.Published }
        check(published.size <= 1) {
            "invariant violated: multiple Published versions for ${key.value}: " +
                published.joinToString(", ") { it.semVer.toString() }
        }
        return published.singleOrNull() ?: throw PromptVersionNotFoundException.forKey(key)
    }
}
