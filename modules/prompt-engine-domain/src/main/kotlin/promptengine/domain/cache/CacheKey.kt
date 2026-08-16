package promptengine.domain.cache

import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.VersionRef

/**
 * [PromptCache]のキー（設計書§5.1 `compiledKey(key, versionRef)`、ADR-0033決定2）。
 *
 * `CompositionMode`はキーに含めない。COMPILE_ONLYの結果はそもそもキャッシュ対象外
 * （ADR-0033決定a）であり、キャッシュに乗る[promptengine.domain.composition.CompiledPrompt]は
 * 常にSTANDARDモードの結果のみのため、値が常に固定の次元をキーへ持ち込まない。
 *
 * [versionRef]が[VersionRef.Latest]/[VersionRef.Alias]の場合の失効は、
 * [PromptCache.invalidateByPrompt]が`versionRef`を問わず[promptKey]の全エントリを
 * 無条件に落とすことで扱う（`versionRef`ごとの個別判定ロジックは持たない）。
 */
data class CacheKey(val promptKey: PromptKey, val versionRef: VersionRef)
