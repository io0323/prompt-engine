package promptengine.engine.execution

import promptengine.domain.execution.ExecutionAdapter
import promptengine.domain.execution.ExecutionOutcome
import promptengine.domain.execution.ExecutionPolicy
import promptengine.domain.execution.RawResponse
import promptengine.domain.parsing.OutputFormatter
import promptengine.domain.parsing.OutputSchema
import promptengine.domain.parsing.ParseFailedException
import promptengine.domain.render.MessageRole
import promptengine.domain.render.OutputFormat
import promptengine.domain.render.RenderedMessage
import promptengine.domain.render.RenderedPrompt
import promptengine.domain.tokenizer.TokenizerPlugin
import promptengine.engine.render.RenderHashCalculator

/**
 * Execution(ステージ9)+Response Parsing(ステージ10)を結合する具象クラス（設計書§5.9シーケンス
 * `PO -> AA: 修復プロンプトで再実行(最大N回)`、ADR-0014決定6）。
 *
 * §16拡張ポイント一覧に「Execution/Response Parsing全体を統括するEngine」は定義されていないため、
 * §3.4疑似コードには存在しない。P6の`RenderEngine`と同様の位置づけで、Pipeline Orchestrator（P8）が
 * 「既存のEngineに委譲するだけの薄い層」であるために必要な結合ロジックとしてP7で新設した。
 *
 * [executionAdapter]は通常[RetryingExecutionAdapter]でラップ済みのものを渡す想定
 * （本クラス自体はリトライを行わない）。
 */
class ExecutionCoordinator(
    private val executionAdapter: ExecutionAdapter,
    private val outputFormatters: Map<OutputFormat, OutputFormatter>,
    private val tokenizerPlugin: TokenizerPlugin,
) {
    /**
     * [rendered]を実行し、結果を[schema]に基づき解析する。解析に失敗し
     * [ExecutionPolicy.parseRepair]が有効なら、修復メッセージを付与した上で
     * [ExecutionPolicy.parseRepair]の`maxAttempts`回まで再実行・再解析する。
     *
     * 全ての試行が失敗した場合は最後の[ParseFailedException]（`reason`を引き継ぐ）を投げる。
     * [ParseFailedException.reason]は構造的な理由のみを含む契約（[ParseFailedException]の
     * KDoc参照）であり、修復ラウンドで付与するメッセージは[RenderedMessage]の`content`
     * （実行に必要なため生値を保持する）にのみ現れ、例外メッセージには現れない（ADR-0014決定9）。
     *
     * 返却する[ExecutionOutcome.attempts]は、解析に失敗し破棄された中間の応答の`content`を
     * [REDACTED_CONTENT]でマスクする。プロバイダへの再送（修復ラウンドの`RenderedMessage`）には
     * 生値をそのまま使うが、Audit/Evaluation側が読み取る記録用データには残さない（ADR-0014決定9、
     * 「プロバイダには送るが記録には残さない」）。最終的に解析へ成功した応答（`attempts.last()`）
     * のみ実値を保持する。
     */
    fun run(
        rendered: RenderedPrompt,
        policy: ExecutionPolicy,
        schema: OutputSchema?,
    ): ExecutionOutcome {
        val formatter =
            outputFormatters[rendered.outputFormat]
                ?: error("no OutputFormatter registered for outputFormat=${rendered.outputFormat}")
        val maxRepairAttempts = if (policy.parseRepair.enabled) policy.parseRepair.maxAttempts else 0

        val attempts = mutableListOf<RawResponse>()
        var currentPrompt = rendered
        var repairAttempt = 0

        while (true) {
            val raw = executionAdapter.execute(currentPrompt, policy)

            val parseFailure =
                try {
                    val parsed = formatter.parse(raw.content, schema)
                    attempts += raw
                    return ExecutionOutcome(parsed, attempts)
                } catch (failure: ParseFailedException) {
                    failure
                }
            attempts += raw.withRedactedContent()

            if (repairAttempt >= maxRepairAttempts) {
                throw ParseFailedException(
                    parseFailure.format,
                    parseFailure.reason,
                    repairAttempts = repairAttempt,
                    cause = parseFailure,
                )
            }
            repairAttempt++
            currentPrompt = buildRepairPrompt(currentPrompt, raw, formatter, schema, parseFailure.reason)
        }
    }

    /**
     * Audit/Evaluation向けの記録（[ExecutionOutcome.attempts]）に載せる前に、解析失敗により
     * 破棄される応答の`content`をマスクする（[run]のKDoc参照）。プロバイダへ再送する
     * [RenderedMessage]の構築には、この関数を経由しない生の[RawResponse]を使うこと。
     */
    private fun RawResponse.withRedactedContent(): RawResponse =
        RawResponse(
            REDACTED_CONTENT,
            usage,
            latency,
            retryCount,
        )

    private fun buildRepairPrompt(
        original: RenderedPrompt,
        failedResponse: RawResponse,
        formatter: OutputFormatter,
        schema: OutputSchema?,
        reason: String,
    ): RenderedPrompt {
        val repairInstruction =
            "先の応答は指定した形式で解釈できませんでした（理由: $reason）。" +
                formatter.instruction(schema) +
                "上記の形式を厳守して再出力してください。"
        val messages =
            original.messages +
                RenderedMessage(MessageRole.ASSISTANT, failedResponse.content) +
                RenderedMessage(MessageRole.USER, repairInstruction)

        val tokenEstimate = tokenizerPlugin.estimate(messages.joinToString(separator = "") { it.content })
        val renderHash = RenderHashCalculator.compute(messages, original.outputFormat, REPAIR_ENGINE_ID)

        return RenderedPrompt(messages, original.outputFormat, tokenEstimate, renderHash)
    }

    companion object {
        /**
         * 修復ラウンドの`RenderedPrompt`はTemplateEngineによるAST展開を経ていないため、
         * `templateEngine.id()`ではなく固定値を用いる（ADR-0014決定6）。
         */
        private const val REPAIR_ENGINE_ID = "pe-repair/1"

        /** [SensitiveValue][promptengine.domain.shared.SensitiveValue]と同じマスク表現。 */
        private const val REDACTED_CONTENT = "***"
    }
}
