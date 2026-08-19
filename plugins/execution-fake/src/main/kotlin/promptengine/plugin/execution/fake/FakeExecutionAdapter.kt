package promptengine.plugin.execution.fake

import promptengine.domain.execution.ExecutionAdapter
import promptengine.domain.execution.ExecutionFailedException
import promptengine.domain.execution.ExecutionPolicy
import promptengine.domain.execution.RawResponse
import promptengine.domain.render.RenderedPrompt
import promptengine.domain.shared.SensitiveValue

/**
 * 実APAP接続（M2）が無いM1時点でPipeline全体をテストするための[ExecutionAdapter]既定実装
 * （実装ガイド§6.8）。
 *
 * 設定された[scenario]に応じて固定の応答を返す/例外を投げるのみで、[execute]の引数
 * （[prompt]・[policy]）の内容には依存しない。リトライは行わない（[promptengine.domain.execution.ExecutionAdapter]の
 * KDoc参照。リトライは`RetryingExecutionAdapter`等の呼出側が付与する）。
 *
 * [activeProfiles]に`"production"`が含まれる場合、実際のプロンプト内容に関わらず固定応答を
 * 返してしまうことは本番トラフィックにとって致命的なため、`InMemoryAuditRepository`/
 * `InMemoryEventBusAdapter`（ADR-0015決定7）と同じ方針で起動時エラー（[IllegalStateException]）
 * とする（P9c、実APAPアダプタはM2で追加予定）。
 */
class FakeExecutionAdapter(
    private val scenario: FakeExecutionScenario,
    activeProfiles: Set<String> = emptySet(),
) : ExecutionAdapter {
    init {
        check(PRODUCTION_PROFILE !in activeProfiles) {
            "FakeExecutionAdapter must not be selected under the '$PRODUCTION_PROFILE' profile: " +
                "it returns a fixed canned response regardless of the actual prompt. " +
                "A real APAP-backed ExecutionAdapter is planned for M2."
        }
    }

    override fun execute(
        prompt: RenderedPrompt,
        policy: ExecutionPolicy,
    ): RawResponse =
        when (val s = scenario) {
            is FakeExecutionScenario.Success -> RawResponse(SensitiveValue.of(s.content), s.usage, s.latency)
            is FakeExecutionScenario.Delayed -> RawResponse(SensitiveValue.of(s.content), s.usage, s.latency)
            is FakeExecutionScenario.Error -> throw ExecutionFailedException(s.errorType, retryCount = 0)
            is FakeExecutionScenario.InvalidStructuredOutput ->
                RawResponse(SensitiveValue.of(s.rawContent), s.usage, s.latency)
            is FakeExecutionScenario.Cycling -> RawResponse(SensitiveValue.of(s.next()), s.usage, s.latency)
        }

    companion object {
        private const val PRODUCTION_PROFILE = "production"
    }
}
