package promptengine.bootstrap.config

import com.fasterxml.jackson.databind.ObjectMapper
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.sync.RedisCommands
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import promptengine.domain.cache.PromptCache
import promptengine.infrastructure.cache.RedisPromptCache

/**
 * `PromptCache`（設計書§16拡張ポイント#9の既定実装、ADR-0033決定d）のDI配線。
 *
 * `production`プロファイルに限定しない: `OutboxRelayConfig`/`SubscriberConfig`と異なり
 * 実Broker（Kafka互換）への接続待ちを持たず、`RedisClient.create`自体は接続を確立しない
 * （実際の接続は最初のコマンド発行時に遅延確立される、Lettuceの既定動作）ため、
 * 非productionプロファイルでBeanを構築しても起動を遅延・失敗させない。
 */
@Configuration
@EnableConfigurationProperties(CacheProperties::class)
class CacheConfig {
    @Bean(destroyMethod = "shutdown")
    fun redisClient(properties: CacheProperties): RedisClient = RedisClient.create(properties.redisUri)

    @Bean(destroyMethod = "close")
    fun redisConnection(redisClient: RedisClient): StatefulRedisConnection<String, String> = redisClient.connect()

    @Bean
    fun redisCommands(connection: StatefulRedisConnection<String, String>): RedisCommands<String, String> =
        connection.sync()

    @Bean
    fun promptCache(
        commands: RedisCommands<String, String>,
        objectMapper: ObjectMapper,
    ): PromptCache = RedisPromptCache(commands, objectMapper)
}
