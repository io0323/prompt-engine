package promptengine.application.pipeline

import promptengine.domain.pipeline.PipelineContext
import promptengine.domain.pipeline.PipelineStage
import promptengine.domain.prompt.LifecycleState
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
 * [VersionRef.Alias]の解決（別名→実Version）は本Stageのスコープ外（未実装。
 * `PromptRepository`にAlias解決の経路が無いため）。指定された場合は
 * [PromptVersionNotFoundException]を投げる（`PROMPT_NOT_FOUND`として扱う。
 * Alias自体の解決に失敗した状態と、Version自体が存在しない状態を呼出元は区別しない）。
 */
class LoadStage(private val promptRepository: PromptRepository) : PipelineStage {
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
                versions.find { it.state == LifecycleState.Published }
                    ?: throw PromptVersionNotFoundException.forKey(key)
            is VersionRef.Alias -> throw PromptVersionNotFoundException.forKey(key)
        }
}
