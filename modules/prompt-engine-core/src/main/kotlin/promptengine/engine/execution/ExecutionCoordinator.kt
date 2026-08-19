package promptengine.engine.execution

import promptengine.domain.execution.ExecutionAdapter
import promptengine.domain.execution.ExecutionEngine
import promptengine.domain.execution.ExecutionOutcome
import promptengine.domain.execution.ExecutionPolicy
import promptengine.domain.execution.RawResponse
import promptengine.domain.optimization.TokenBudgetExceededException
import promptengine.domain.parsing.OutputFormatter
import promptengine.domain.parsing.OutputSchema
import promptengine.domain.parsing.ParseFailedException
import promptengine.domain.render.MessageRole
import promptengine.domain.render.OutputFormat
import promptengine.domain.render.RenderedMessage
import promptengine.domain.render.RenderedPrompt
import promptengine.domain.shared.SensitiveValue
import promptengine.domain.shared.TokenCount
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
 * （本クラス自体はリトライを行わない）。[ExecutionEngine]の実装（ADR-0015決定1: Pipeline
 * Orchestrator、`prompt-engine-application`の依存性逆転のみを理由にdomain Interface化）。
 */
class ExecutionCoordinator(
    private val executionAdapter: ExecutionAdapter,
    private val outputFormatters: Map<OutputFormat, OutputFormatter>,
    private val tokenizerPlugin: TokenizerPlugin,
) : ExecutionEngine {
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
     *
     * 修復ラウンドは直前の失敗応答と修復指示を`messages`へ追加していくため、繰り返すたびに
     * `tokenEstimate`が増加する。各修復プロンプトの構築後、次の[ExecutionAdapter.execute]を
     * 呼ぶ前に[budget]（Stage 7 Optimizationが検証した初回`rendered`の予算と同じ枠）を
     * 超えていないか検証し、超えていれば[TokenBudgetExceededException]を投げて打ち切る
     * （CodeRabbitレビュー指摘: 未検証のまま再送するとプロバイダ側のcontext上限エラーとして
     * 不透明に失敗し、ドメインのエラーコードに正しくマッピングされない）。初回`rendered`自体は
     * Stage 7で既に検証済みのためここでは再検証しない。
     */
    override fun run(
        rendered: RenderedPrompt,
        policy: ExecutionPolicy,
        schema: OutputSchema?,
        budget: TokenCount,
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
                    val parsed = formatter.parse(raw.content.expose(), schema)
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
            if (currentPrompt.tokenEstimate.value > budget.value) {
                throw TokenBudgetExceededException(currentPrompt.tokenEstimate, budget)
            }
        }
    }

    /**
     * Audit/Evaluation向けの記録（[ExecutionOutcome.attempts]）に載せる前に、解析失敗により
     * 破棄される応答の`content`をマスクする（[run]のKDoc参照）。プロバイダへ再送する
     * [RenderedMessage]の構築には、この関数を経由しない生の[RawResponse]を使うこと。
     */
    private fun RawResponse.withRedactedContent(): RawResponse =
        RawResponse(
            SensitiveValue.of(REDACTED_CONTENT),
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
        // 通常render経路（RenderEngineImpl）と同じくRenderHashCalculator.buildへ未正規化のまま
        // 渡す。正規化とハッシュ算出はbuild内で不可分に行われるため、ここで正規化を呼び忘れても
        // 検知できない状態は構造的に発生しない（CodeRabbitレビュー指摘、RenderHashCalculatorの
        // KDoc参照）。
        val messages =
            original.messages +
                RenderedMessage(MessageRole.ASSISTANT, failedResponse.content.expose()) +
                RenderedMessage(MessageRole.USER, repairInstruction)

        return RenderHashCalculator.build(
            messages,
            original.outputFormat,
            REPAIR_ENGINE_ID,
            tokenizerPlugin,
            modelHints = original.modelHints,
        )
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
