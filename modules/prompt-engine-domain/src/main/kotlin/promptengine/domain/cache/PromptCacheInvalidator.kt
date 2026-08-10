package promptengine.domain.cache

import promptengine.domain.prompt.PromptKey

/**
 * Prompt単位のキャッシュ無効化ポート（設計書§14 `CacheInvalidated`、ADR-0026決定6）。
 *
 * `PromptPublished`等を購読する`CacheInvalidationSubscriber`
 * （`prompt-engine-infrastructure`）が、配信Versionが切り替わったPromptについて呼ぶ。
 *
 * M1時点でPE内にRender結果／Composition結果の永続キャッシュ実装は存在しないため、
 * 既定の実装（`InMemoryPromptCacheInvalidator`）は無効化されたkeyを記録するだけの
 * 最小実装とする。購読・呼出しの配線自体は本物であり、実キャッシュ（Redis等、設計書§2.14）が
 * 入った時点で本Interfaceの実装を差し替えるだけで済む。
 */
interface PromptCacheInvalidator {
    /** [key]に紐づくキャッシュエントリを一括で無効化する。 */
    fun invalidateByPrompt(key: PromptKey)
}
