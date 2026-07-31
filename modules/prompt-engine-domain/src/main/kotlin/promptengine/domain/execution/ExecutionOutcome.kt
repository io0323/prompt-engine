package promptengine.domain.execution

import promptengine.domain.parsing.ParsedOutput

/**
 * Execution(ステージ9)+Response Parsing(ステージ10)の最終結果（ADR-0014決定2・決定8）。
 *
 * [attempts]は初回実行＋修復のための再実行（あれば）の[RawResponse]を実行順に保持する
 * （`attempts[0]`が初回実行）。各要素が自身の`usage`/`latency`/`retryCount`を保持するため、
 * Audit/Evaluation側はこのリストを合算すれば修復にかかったトークン・コストを含め追跡できる
 * （専用の集約型`OptimizationReport`相当は新設しない、ADR-0014決定8）。
 */
@ConsistentCopyVisibility
data class ExecutionOutcome private constructor(
    val parsedOutput: ParsedOutput,
    val attempts: List<RawResponse>,
) {
    init {
        require(attempts.isNotEmpty()) { "attempts must not be empty" }
    }

    companion object {
        /** [attempts]を不変コピー（[List.toList]）してから保持する。 */
        operator fun invoke(
            parsedOutput: ParsedOutput,
            attempts: List<RawResponse>,
        ): ExecutionOutcome = ExecutionOutcome(parsedOutput, attempts.toList())
    }
}
