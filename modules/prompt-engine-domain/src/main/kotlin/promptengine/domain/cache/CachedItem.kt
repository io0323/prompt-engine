package promptengine.domain.cache

import promptengine.domain.composition.CompiledPrompt

/** [PromptCache]が保持する1エントリ（設計書§3.4疑似コード）。 */
data class CachedItem(val compiledPrompt: CompiledPrompt)
