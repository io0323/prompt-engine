package promptengine.application.pipeline

import promptengine.domain.cache.CacheKey
import promptengine.domain.cache.CachedItem
import promptengine.domain.cache.PromptCache
import promptengine.domain.composition.CompiledPrompt
import promptengine.domain.composition.CompositionMode
import promptengine.domain.composition.CompositionService
import promptengine.domain.pipeline.PipelineContext
import promptengine.domain.pipeline.PipelineMode
import promptengine.domain.pipeline.PipelineStage
import promptengine.domain.prompt.PromptVersion
import java.time.Duration

/**
 * Stage 2（Merge、設計書§2.6）。[CompositionService.compile]はextends解決・import/include
 * 解決・macro展開を1回の呼び出しでまとめて行う設計が既に確立している（P3c、ADR-0009/0010）ため、
 * このStageがStage 3（Import）分も含めて委譲する（ADR-0015決定2の追加適用。[ImportStage]は
 * 素通しステージ）。
 *
 * [PromptCache]（設計書§5.1/§5.2、ADR-0033）はSTANDARDモードのみ経由する。COMPILE_ONLYは
 * Draft状態の参照を許可する（ADR-0024）ため、Draftの更新に無効化イベントが伴わずキャッシュが
 * 恒久的に古くなる穴になる。CI検証用の経路でホットパスでもないため、キャッシュを
 * 一切経由させない（ADR-0033決定a）。
 */
class MergeStage(
    private val compositionService: CompositionService,
    private val promptCache: PromptCache,
    private val ttl: Duration = Duration.ofSeconds(DEFAULT_TTL_SECONDS),
) : PipelineStage {
    override val name: String = "Merge"

    override fun execute(context: PipelineContext): PipelineContext {
        val promptVersion =
            checkNotNull(context.promptVersion) { "MergeStage requires promptVersion (Stage 1 Load must run first)" }
        val compositionMode =
            if (context.mode == PipelineMode.COMPILE_ONLY) CompositionMode.COMPILE_ONLY else CompositionMode.STANDARD

        if (compositionMode == CompositionMode.COMPILE_ONLY) {
            val compiled = compositionService.compile(context.request.promptKey, promptVersion, compositionMode)
            return context.copy(compiled = compiled)
        }
        return context.copy(compiled = resolveCachedOrCompile(context, promptVersion, compositionMode))
    }

    private fun resolveCachedOrCompile(
        context: PipelineContext,
        promptVersion: PromptVersion,
        compositionMode: CompositionMode,
    ): CompiledPrompt {
        val cacheKey = CacheKey(context.request.promptKey, context.request.versionRef)
        val cached = promptCache.get(cacheKey)?.compiledPrompt
        if (cached != null) return cached

        val compiled = compositionService.compile(context.request.promptKey, promptVersion, compositionMode)
        promptCache.put(cacheKey, CachedItem(compiled), ttl)
        return compiled
    }

    private companion object {
        /** イベント無効化が届く前に読まれる窓の上限（ADR-0033決定5・b、OutboxRelayProperties.claimTimeoutSecondsの既定と揃える）。 */
        const val DEFAULT_TTL_SECONDS = 30L
    }
}
