package promptengine.application.pipeline

import promptengine.domain.pipeline.PipelineContext
import promptengine.domain.pipeline.PipelineStage
import promptengine.domain.prompt.LifecycleState
import promptengine.domain.prompt.PromptAliasRepository
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.PromptRepository
import promptengine.domain.prompt.PromptVersion
import promptengine.domain.prompt.PromptVersionNotFoundException
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
 * [VersionRef.Latest]の解決は`versions.find { Published }`ではなく`singleOrNull`を使う
 * （CodeRabbitレビュー指摘への対応）。`Prompt.init`が「Publishedは同時に1件まで」という
 * 不変条件を保証しているため`.find`でも実害は無いという指摘への反論自体は正しいが、
 * 将来この不変条件が変更・破られた場合、`.find`は複数件存在しても先頭の1件を無言で
 * 返してしまう。`singleOrNull`にしておけば、不変条件が壊れた場合に
 * `IllegalArgumentException`で即座に落ちる（`INTERNAL_ERROR`として検出できる）ため、
 * 静かに壊れる経路を無くす。
 */
class LoadStage(
    private val promptRepository: PromptRepository,
    private val aliasRepository: PromptAliasRepository,
) : PipelineStage {
    override val name: String = "Load"

    override fun execute(context: PipelineContext): PipelineContext {
        val key = context.request.promptKey
        val prompt = promptRepository.findByKey(key) ?: throw PromptVersionNotFoundException.forKey(key)

        val version = resolveVersion(prompt.versions, context.request.versionRef, key)
        return context.copy(promptVersion = version)
    }

    private fun resolveVersion(
        versions: List<PromptVersion>,
        versionRef: VersionRef,
        key: PromptKey,
    ): PromptVersion =
        when (versionRef) {
            is VersionRef.Fixed ->
                versions.find { it.semVer == versionRef.semVer }
                    ?: throw PromptVersionNotFoundException(versionRef.semVer)
            is VersionRef.Latest ->
                versions.singleOrNull { it.state == LifecycleState.Published }
                    ?: throw PromptVersionNotFoundException.forKey(key)
            is VersionRef.Alias -> {
                val target =
                    aliasRepository.find(key, versionRef.name)?.semVer
                        ?: throw PromptVersionNotFoundException.forKey(key)
                versions.find { it.semVer == target }
                    ?: throw PromptVersionNotFoundException(target)
            }
        }
}
