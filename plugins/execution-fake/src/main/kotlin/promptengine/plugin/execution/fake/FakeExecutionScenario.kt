package promptengine.plugin.execution.fake

import promptengine.domain.execution.ExecutionErrorType
import promptengine.domain.execution.Usage
import promptengine.domain.shared.LatencyMs

/**
 * [FakeExecutionAdapter]の挙動を切り替えるシナリオ（実装ガイド§6.8、テストで使うため
 * 決定的に振る舞う設計）。
 *
 * いずれのシナリオも、実際の`prompt`引数の内容に関わらず設定値をそのまま返す/投げる
 * 純粋な構成であり、内部状態・現在時刻・乱数を一切使わない。そのため同一シナリオでの
 * 呼出は常に同一の結果になる。
 */
sealed interface FakeExecutionScenario {
    /** 正常応答。 */
    data class Success(val content: String, val usage: Usage, val latency: LatencyMs) : FakeExecutionScenario

    /** 遅延応答（レイテンシ値を大きく設定するのみで、実際にスレッドを待機させない）。 */
    data class Delayed(val content: String, val usage: Usage, val latency: LatencyMs) : FakeExecutionScenario

    /** エラー応答。 */
    data class Error(val errorType: ExecutionErrorType) : FakeExecutionScenario

    /** 不正JSON等、構造化フォーマットとして解析に失敗する応答。 */
    data class InvalidStructuredOutput(val rawContent: String, val usage: Usage, val latency: LatencyMs) :
        FakeExecutionScenario
}
