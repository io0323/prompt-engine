package promptengine.bootstrap.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import promptengine.domain.cache.PromptCacheInvalidator
import promptengine.domain.dlq.DeadLetterQueueRepository
import promptengine.domain.evaluation.EvaluationEngine
import promptengine.domain.evaluation.EvaluationRepository
import promptengine.domain.evaluation.EvaluationRule
import promptengine.domain.evaluation.EvaluationRuleFailureHandler
import promptengine.domain.evaluation.ExecutionLogRepository
import promptengine.domain.prompt.ArchiveEligibilityRepository
import promptengine.domain.search.PromptSearchIndexer
import promptengine.engine.evaluation.CostEvaluationRule
import promptengine.engine.evaluation.EvaluationEngineImpl
import promptengine.engine.evaluation.LatencyEvaluationRule
import promptengine.engine.evaluation.TokenUsageEvaluationRule
import promptengine.infrastructure.cache.InMemoryPromptCacheInvalidator
import promptengine.infrastructure.evaluation.Slf4jEvaluationRuleFailureHandler
import promptengine.infrastructure.persistence.JdbcArchiveEligibilityRepository
import promptengine.infrastructure.persistence.JdbcDeadLetterQueueRepository
import promptengine.infrastructure.persistence.JdbcEvaluationRepository
import promptengine.infrastructure.persistence.JdbcExecutionLogRepository
import promptengine.infrastructure.search.InMemoryPromptSearchIndexer

/**
 * Evaluation / 実行ログ / DLQ / archiveガード / Cache / Search のDI配線（P10b、ADR-0026）。
 *
 * [RepositoryConfig]へ足さず別クラスに分けているのは、detektのTooManyFunctions閾値対策
 * （[AuditEventConfig]・[QueryHandlersConfig]が同じ理由で分割されているのと同じ）。
 *
 * これらのBeanは`production`プロファイルに限定しない。[SubscriberConfig]（購読側の駆動）は
 * 実Brokerを要するため`production`限定だが、ここで定義するのはRepositoryとPure Engineであり、
 * `ArchiveHandler`のガード（[ArchiveEligibilityRepository]）は全プロファイルで必要なため。
 */
@Configuration
class EvaluationConfig {
    @Bean
    fun executionLogRepository(jdbcTemplate: NamedParameterJdbcTemplate): ExecutionLogRepository =
        JdbcExecutionLogRepository(jdbcTemplate)

    @Bean
    fun evaluationRepository(jdbcTemplate: NamedParameterJdbcTemplate): EvaluationRepository =
        JdbcEvaluationRepository(jdbcTemplate)

    @Bean
    fun deadLetterQueueRepository(jdbcTemplate: NamedParameterJdbcTemplate): DeadLetterQueueRepository =
        JdbcDeadLetterQueueRepository(jdbcTemplate)

    @Bean
    fun archiveEligibilityRepository(jdbcTemplate: NamedParameterJdbcTemplate): ArchiveEligibilityRepository =
        JdbcArchiveEligibilityRepository(jdbcTemplate)

    @Bean
    fun evaluationRuleFailureHandler(): EvaluationRuleFailureHandler = Slf4jEvaluationRuleFailureHandler()

    /**
     * M1の評価器3種（設計書§2.12 Latency / Token Usage / Cost）。Quality系はPluginとして
     * [EvaluationRule]を実装したBeanを足すだけで拡張できる（[evaluationEngine]がリストを
     * そのまま受け取るため、本メソッドの変更を要さない）。
     */
    @Bean
    fun m1EvaluationRules(): List<EvaluationRule> =
        listOf(LatencyEvaluationRule(), TokenUsageEvaluationRule(), CostEvaluationRule())

    @Bean
    fun evaluationEngine(
        rules: List<EvaluationRule>,
        failureHandler: EvaluationRuleFailureHandler,
    ): EvaluationEngine = EvaluationEngineImpl(rules, failureHandler)

    /** M1は実キャッシュが無いため記録のみの実装（ADR-0026決定6）。 */
    @Bean
    fun promptCacheInvalidator(): PromptCacheInvalidator = InMemoryPromptCacheInvalidator()

    /** M1は簡易実装でよい（`docs/prompts/p10b.md`、ADR-0026決定6）。 */
    @Bean
    fun promptSearchIndexer(): PromptSearchIndexer = InMemoryPromptSearchIndexer()
}
