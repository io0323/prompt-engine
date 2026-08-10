package promptengine.domain.evaluation

/**
 * 1回のPipeline Full-execution実行の成否（`execution_logs.status`、設計書§12）。
 *
 * M1で実際に記録されるのは[SUCCESS]のみ。`PromptExecuted`（設計書§14）はPipeline Stage 11
 * （Evaluation）が発行し、Stage 9（Execution）が成功してStage 11まで到達した場合にしか
 * 発火しないため。実行失敗は別イベント`PromptExecutionFailed`として設計書§14に定義されて
 * いるが、その発火元はM1時点で存在しない（ADR-0026「既知の限界」）。[FAILED]は
 * `execution_logs.status`列が取りうる値をドメイン側で先に閉じた集合として表現しておくために
 * 定義する。
 */
enum class ExecutionStatus {
    SUCCESS,
    FAILED,
}
