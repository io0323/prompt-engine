package promptengine.bootstrap.config

import com.fasterxml.jackson.databind.ObjectMapper
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.sync.RedisCommands
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import promptengine.domain.cache.PromptCache
import promptengine.infrastructure.cache.NoopPromptCache
import promptengine.infrastructure.cache.RedisPromptCache

/**
 * `PromptCache`（設計書§16拡張ポイント#9の既定実装、ADR-0033決定d）のDI配線。
 *
 * [redisClient]/[redisConnection]/[redisCommands]/[promptCacheProduction]は`production`
 * プロファイル限定（[AuditEventConfig]の`auditRepositoryProduction`と同じ構成）。
 * `RedisClient.create`自体は接続を確立しないが、[redisConnection]が呼ぶ
 * `RedisClient.connect()`はLettuceの同期APIとして呼び出し時点で即座にTCP接続を確立する
 * （非同期の`connectAsync()`とは異なる）。このため非productionプロファイルでもBeanを
 * 無条件に構築すると、Redis未起動の開発環境やRedisを起動しないSpringコンテキストテスト
 * （`ActuatorHealthProbeSecurityTest`等）が`RedisConnectionException`で起動失敗していた
 * （PR #109 CIで発覚）。
 */
@Configuration
@EnableConfigurationProperties(CacheProperties::class)
class CacheConfig {
    @Bean(destroyMethod = "shutdown")
    @Profile("production")
    fun redisClient(properties: CacheProperties): RedisClient = RedisClient.create(properties.redisUri)

    @Bean(destroyMethod = "close")
    @Profile("production")
    fun redisConnection(redisClient: RedisClient): StatefulRedisConnection<String, String> = redisClient.connect()

    @Bean
    @Profile("production")
    fun redisCommands(connection: StatefulRedisConnection<String, String>): RedisCommands<String, String> =
        connection.sync()

    /** キャッシュ本来の実装（`production`プロファイルではこちらを使う、ADR-0033決定d）。 */
    @Bean
    @Profile("production")
    fun promptCacheProduction(
        commands: RedisCommands<String, String>,
        objectMapper: ObjectMapper,
    ): PromptCache = RedisPromptCache(commands, objectMapper)

    /**
     * ローカル開発・テスト用の既定（[NoopPromptCache]のクラスKDoc参照）。`production`
     * プロファイルでは[promptCacheProduction]が選ばれるため生成されない。
     */
    @Bean
    @Profile("!production")
    fun promptCacheDefault(): PromptCache = NoopPromptCache()
}
