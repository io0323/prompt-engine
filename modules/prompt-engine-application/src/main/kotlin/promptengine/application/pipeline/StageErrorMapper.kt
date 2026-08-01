package promptengine.application.pipeline

import promptengine.domain.composition.CircularDependencyException
import promptengine.domain.composition.FragmentReferenceNotFoundException
import promptengine.domain.composition.TemplateReferenceNotFoundException
import promptengine.domain.context.ContextUnavailableException
import promptengine.domain.execution.ExecutionFailedException
import promptengine.domain.optimization.TokenBudgetExceededException
import promptengine.domain.parsing.ParseFailedException
import promptengine.domain.pipeline.InvalidPipelineRequestException
import promptengine.domain.prompt.PromptVersionNotFoundException
import promptengine.domain.render.RenderFailedException
import promptengine.domain.validation.ValidationFailedException
import promptengine.domain.variable.VariableUnresolvedException

/**
 * `StageError`（各Stageが投げるdomain例外）を設計書§13.3のエラーコードへ写像する
 * 唯一の集約点（実装ガイド§6.9、ADR-0015決定4）。
 *
 * 例外の型のみを判定基準とする（ステージ名による補助判定は行わない、ADR-0015決定4修正）。
 * `RENDER_ERROR`は[RenderFailedException]という専用型にのみ対応する。ステージ自身の
 * `checkNotNull`（前段ステージ未実行の防御コード）が投げる`IllegalStateException`は
 * この型ではないため`INTERNAL_ERROR`にフォールバックし、`RENDER_ERROR`と誤って
 * 混同されることはない。`CompositionException`のサブタイプのうち上記3種以外は、
 * §13.3にコードが定義されていないため`INTERNAL_ERROR`へフォールバックする
 * （`CompositionException`自身のKDoc参照）。
 */
object StageErrorMapper {
    const val PROMPT_NOT_FOUND = "PROMPT_NOT_FOUND"
    const val TEMPLATE_NOT_FOUND = "TEMPLATE_NOT_FOUND"
    const val CIRCULAR_DEPENDENCY = "CIRCULAR_DEPENDENCY"
    const val FRAGMENT_NOT_FOUND = "FRAGMENT_NOT_FOUND"
    const val VARIABLE_UNRESOLVED = "VARIABLE_UNRESOLVED"
    const val CONTEXT_UNAVAILABLE = "CONTEXT_UNAVAILABLE"
    const val VALIDATION_FAILED = "VALIDATION_FAILED"
    const val TOKEN_BUDGET_EXCEEDED = "TOKEN_BUDGET_EXCEEDED"
    const val RENDER_ERROR = "RENDER_ERROR"
    const val EXECUTION_FAILED = "EXECUTION_FAILED"
    const val PARSE_FAILED = "PARSE_FAILED"
    const val INVALID_REQUEST = "INVALID_REQUEST"
    const val INTERNAL_ERROR = "INTERNAL_ERROR"

    fun errorCodeFor(throwable: Throwable): String =
        when (throwable) {
            is PromptVersionNotFoundException -> PROMPT_NOT_FOUND
            is TemplateReferenceNotFoundException -> TEMPLATE_NOT_FOUND
            is CircularDependencyException -> CIRCULAR_DEPENDENCY
            is FragmentReferenceNotFoundException -> FRAGMENT_NOT_FOUND
            is VariableUnresolvedException -> VARIABLE_UNRESOLVED
            is ContextUnavailableException -> CONTEXT_UNAVAILABLE
            is ValidationFailedException -> VALIDATION_FAILED
            is TokenBudgetExceededException -> TOKEN_BUDGET_EXCEEDED
            is RenderFailedException -> RENDER_ERROR
            is ExecutionFailedException -> EXECUTION_FAILED
            is ParseFailedException -> PARSE_FAILED
            is InvalidPipelineRequestException -> INVALID_REQUEST
            else -> INTERNAL_ERROR
        }
}
