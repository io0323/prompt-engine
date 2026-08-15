package promptengine.domain.cache

import promptengine.domain.prompt.PromptKey
import java.time.Duration

/**
 * CompiledPromptのキャッシュ（設計書§3.4疑似コード、§16拡張ポイント#9、ADR-0033）。
 * 既定実装はRedis（`prompt-engine-infrastructure`の`RedisPromptCache`）。
 *
 * [get]/[put]は`MergeStage`（Stage 2、STANDARDモードのみ）からのみ呼ばれる
 * （COMPILE_ONLYはキャッシュ対象外、ADR-0033決定a）。
 *
 * [invalidateByPrompt]は`CacheInvalidationSubscriber`が、Prompt/Template/Fragmentの
 * publish系イベントを購読して呼ぶ。Template/Fragment起点の場合は、逆依存
 * （`DependencyRepository`、SemVer範囲一致）で影響を受けるPromptKeyを特定してから呼ぶ
 * （ADR-0033決定3）。`versionRef`を問わず[key]の全エントリを無条件に落とす
 * （[CacheKey]のKDoc参照）。
 */
interface PromptCache {
    /** [key]に対応するキャッシュエントリ。無ければ`null`。 */
    fun get(key: CacheKey): CachedItem?

    /** [key]へ[item]を[ttl]（無効化イベントが届く前に読まれる窓の上限、既定30秒）で保存する。 */
    fun put(
        key: CacheKey,
        item: CachedItem,
        ttl: Duration,
    )

    /** [key]に紐づくキャッシュエントリを（`versionRef`を問わず）一括で無効化する。 */
    fun invalidateByPrompt(key: PromptKey)
}
