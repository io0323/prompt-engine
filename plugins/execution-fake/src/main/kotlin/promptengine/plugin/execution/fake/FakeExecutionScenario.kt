package promptengine.plugin.execution.fake

import promptengine.domain.execution.ExecutionErrorType
import promptengine.domain.execution.Usage
import promptengine.domain.shared.LatencyMs
import java.util.concurrent.atomic.AtomicInteger

/**
 * [FakeExecutionAdapter]の挙動を切り替えるシナリオ（実装ガイド§6.8、テストで使うため
 * 決定的に振る舞う設計）。
 *
 * [Success]/[Delayed]/[Error]/[InvalidStructuredOutput]は、実際の`prompt`引数の内容に関わらず
 * 設定値をそのまま返す/投げる純粋な構成であり、内部状態・現在時刻・乱数を一切使わない。
 * そのため同一シナリオでの呼出は常に同一の結果になる。
 *
 * [Cycling]のみ例外的に呼出順の内部状態（呼出回数）を持つ（ADR-0035フェーズ(c)）。
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

    /**
     * [responses]を呼出順に巡回して返す（ADR-0035決定5「Fakeアダプタ前提でよいか」）。
     * Consistency/Determinismが「出力が一致する場合」と「ばらつく場合」を区別できることを
     * テストするために必要（固定1レスポンスの[Success]では表現できない）。
     *
     * 呼出回数は`AtomicInteger`で管理する（複数ワーカーインスタンスが同一の[FakeExecutionAdapter]
     * を共有して並行実行する統合テスト、例えば2ワーカーが同一項目を二重実行しないことの検証で
     * スレッドセーフである必要があるため）。[responses]の末尾まで進むと先頭へ戻る。
     */
    class Cycling(private val responses: List<String>, val usage: Usage, val latency: LatencyMs) :
        FakeExecutionScenario {
        init {
            require(responses.isNotEmpty()) { "responses must not be empty" }
        }

        private val callCount = AtomicInteger(0)

        internal fun next(): String = responses[callCount.getAndIncrement() % responses.size]

        /** これまでの[next]呼出回数（テストが実行回数を検証するために公開する）。 */
        fun invocationCount(): Int = callCount.get()
    }
}
