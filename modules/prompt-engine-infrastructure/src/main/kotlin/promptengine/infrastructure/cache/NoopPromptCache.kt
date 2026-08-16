package promptengine.infrastructure.cache

import promptengine.domain.cache.CacheKey
import promptengine.domain.cache.CachedItem
import promptengine.domain.cache.PromptCache
import promptengine.domain.prompt.PromptKey
import java.time.Duration

/**
 * [PromptCache]の非production既定実装（ADR-0033、[AuditEventConfig][promptengine.bootstrap.config.AuditEventConfig]の
 * `InMemoryAuditRepository`/`InMemoryEventBusAdapter`と同じ「production以外は接続不要な既定実装」パターン）。
 *
 * 常にキャッシュミスとして振る舞う（[get]は常に`null`、[put]/[invalidateByPrompt]は何もしない）。
 * MergeStageはキャッシュミス時にCompositionServiceへフォールバックする既定経路を持つため
 * （NFR-001「Read系はキャッシュで縮退継続」）、これはRedis未接続時の安全なデグレードであり、
 * `InMemoryAuditRepository`のような「production誤用時に起動時エラーとする」自己ガードは持たない
 * （キャッシュ不在はデータ欠落や監査証跡の欠落を伴わない、性能特性のみの差のため）。
 */
class NoopPromptCache : PromptCache {
    override fun get(key: CacheKey): CachedItem? = null

    override fun put(
        key: CacheKey,
        item: CachedItem,
        ttl: Duration,
    ) {
        // 非productionでは何もしない（クラスKDoc参照）。
    }

    override fun invalidateByPrompt(key: PromptKey) {
        // 非productionでは何もしない（クラスKDoc参照）。
    }
}
