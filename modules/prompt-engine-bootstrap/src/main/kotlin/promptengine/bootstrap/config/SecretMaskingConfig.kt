package promptengine.bootstrap.config

import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import promptengine.infrastructure.masking.SensitiveValueMaskingModule

/**
 * [promptengine.domain.shared.SensitiveValue]のJSONマスクをアプリケーション全体の
 * `ObjectMapper`へ適用する（ADR-0026決定4、Secretマスクの第1層）。
 *
 * ## なぜグローバルに掛けるのか
 * CLAUDE.md「Secret / sensitive=trueの変数値は絶対に出力しない」は出力経路を限定しない
 * 無条件の禁止であり、監査ログ（`audit_logs`）・Outbox（`event_bus_outbox` /
 * `domain_events`）・REST APIレスポンスのいずれについても等しく成り立つべき性質。
 * 経路ごとに専用の`ObjectMapper`を用意して掛け忘れる余地を残すより、単一の
 * `ObjectMapper`に対して「この型は常に`***`」と一度宣言する方が、
 * 新しいシリアライズ経路が追加されても自動的に保護される。
 *
 * [promptengine.domain.shared.SensitiveValue]は`expose()`という明示的な取り出し口を持つ。
 * 生値が必要な経路（Render直前のSecret解決等）はそちらを使うため、本設定が
 * 正当な利用を妨げることはない。
 */
@Configuration
class SecretMaskingConfig {
    @Bean
    fun sensitiveValueMaskingCustomizer(): Jackson2ObjectMapperBuilderCustomizer =
        Jackson2ObjectMapperBuilderCustomizer { builder -> builder.modulesToInstall(SensitiveValueMaskingModule()) }
}
