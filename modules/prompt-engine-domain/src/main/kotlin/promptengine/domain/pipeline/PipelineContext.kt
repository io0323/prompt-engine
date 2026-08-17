package promptengine.domain.pipeline

import promptengine.domain.composition.CompiledPrompt
import promptengine.domain.context.ContextBindingSet
import promptengine.domain.execution.ExecutionOutcome
import promptengine.domain.parsing.ParsedOutput
import promptengine.domain.prompt.PromptVersion
import promptengine.domain.render.RenderedPrompt
import promptengine.domain.validation.ValidationReport
import promptengine.domain.variable.BindingSet
import java.util.UUID

/**
 * Pipeline全ステージが受け渡す累積状態（設計書§3.4疑似コード`PipelineContext`、
 * ADR-0015決定3）。
 *
 * §3.4疑似コードの`ast`/`rawResponse`は、実際にP3c〜P7が確立した型（`CompiledPrompt`・
 * `ExecutionOutcome`）に読み替える（ADR-0012がP5で`ExpandedAst`を`CompiledPrompt.body`に
 * 読み替えたのと同じ扱い）。`executionOutcome`はExecution(ステージ9)+Response
 * Parsing(ステージ10)の統合結果（ADR-0014決定6・ADR-0015決定2）であり、`parsedOutput`は
 * そこから転記された値。
 *
 * [mode]は`PipelineFactory`がステージのサブセットを選択するためだけでなく、個々のStage自身の
 * 挙動にも影響する（例: `MergeStage`は`mode == COMPILE_ONLY`かどうかで`CompositionMode`を
 * 切り替える、設計書§2.10 DependencyValidation「Draft相互参照はCompile-onlyで許可」）ため、
 * `PipelineContext`自体が保持する（実装時に判明した補正。当初はStage側が関知しない設計を
 * 想定していたが、Stageの実装がmodeに応じた分岐を必要とすることが分かったため）。
 *
 * その他の各フィールドは[mode]・実行済みステージ数に応じて`null`のままの場合がある
 * （例: COMPILE_ONLYは`rendered`以降が常に`null`）。フィールドが埋まっているかどうかは
 * 呼出元が判断する契約とする。
 *
 * [stageDurationsMs]は`PipelineOrchestrator`が各Stageの`execute()`実行後に
 * `System.nanoTime()`計測値を積み増していく累積マップ（Stage名→ミリ秒）。
 * `AuditStage`（ステージ12）がこれを読み取り[promptengine.domain.audit.AuditRecord]へ転記する。
 *
 * 不変更新（各Stageは新しい`PipelineContext`を`copy()`で返す。設計書§2.6冒頭
 * 「`PipelineContext`（累積状態）を受け渡す」）。
 *
 * [experimentVariantId]は[LoadStage][promptengine.application.pipeline.LoadStage]が
 * `request.preResolvedVersion`（[ExperimentResolvedVersion]）経由で解決した場合のみ設定される
 * （ADR-0034決定4）。`EvaluationStage`（ステージ11）が`PromptExecuted`イベントの
 * `variantId`へ転記し、`execution_logs`/`evaluation_records`の`variant_id`列（設計書§12）
 * まで伝搬する。通常経路の実行では`null`のまま。
 */
data class PipelineContext(
    val request: PipelineRequest,
    val mode: PipelineMode,
    val traceId: String,
    val promptVersion: PromptVersion? = null,
    val compiled: CompiledPrompt? = null,
    val variableBindings: BindingSet? = null,
    val contextBindings: ContextBindingSet? = null,
    val validationReport: ValidationReport? = null,
    val rendered: RenderedPrompt? = null,
    val executionOutcome: ExecutionOutcome? = null,
    val parsedOutput: ParsedOutput? = null,
    val stageDurationsMs: Map<String, Long> = emptyMap(),
    val experimentVariantId: UUID? = null,
)
