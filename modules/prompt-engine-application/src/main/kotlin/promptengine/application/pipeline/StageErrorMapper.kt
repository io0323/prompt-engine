package promptengine.application.pipeline

import promptengine.domain.composition.CircularDependencyException
import promptengine.domain.composition.CompositionDepthExceededException
import promptengine.domain.composition.CompositionSizeExceededException
import promptengine.domain.composition.DraftReferenceNotAllowedException
import promptengine.domain.composition.FragmentReferenceNotFoundException
import promptengine.domain.composition.MacroRecursionException
import promptengine.domain.composition.NestedPromptNotSupportedException
import promptengine.domain.composition.TemplateReferenceNotFoundException
import promptengine.domain.context.ContextUnavailableException
import promptengine.domain.execution.ExecutionFailedException
import promptengine.domain.optimization.TokenBudgetExceededException
import promptengine.domain.parsing.ParseFailedException
import promptengine.domain.pipeline.InvalidPipelineRequestException
import promptengine.domain.prompt.PromptVersionNotFoundException
import promptengine.domain.prompt.PromptVersionStateNotAllowedException
import promptengine.domain.render.RenderFailedException
import promptengine.domain.validation.ValidationFailedException
import promptengine.domain.variable.VariableUnresolvedException
import kotlin.reflect.KClass

/**
 * `StageError`（各Stageが投げるdomain例外）を設計書§13.3のエラーコードへ写像する
 * 唯一の集約点（実装ガイド§6.9、ADR-0015決定4）。
 *
 * 例外の型のみを判定基準とする（ステージ名による補助判定は行わない、ADR-0015決定4修正）。
 * `RENDER_ERROR`は[RenderFailedException]という専用型にのみ対応する。ステージ自身の
 * `checkNotNull`（前段ステージ未実行の防御コード）が投げる`IllegalStateException`は
 * この型ではないため`INTERNAL_ERROR`にフォールバックし、`RENDER_ERROR`と誤って
 * 混同されることはない。`CompositionException`のサブタイプのうち[MacroRecursionException]・
 * [CompositionDepthExceededException]・[CompositionSizeExceededException]・
 * [DraftReferenceNotAllowedException]・[NestedPromptNotSupportedException]・
 * 上記3種（Circular/Template/Fragment）を除く残り（`SuperWithoutParentBlockException`等の
 * DSL構文エラー系）は、§13.3にコードが定義されていないため`INTERNAL_ERROR`へフォールバックする
 * （`CompositionException`自身のKDoc参照、ADR-0021が対象外とした範囲）。
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
    const val COMPOSITION_LIMIT_EXCEEDED = "COMPOSITION_LIMIT_EXCEEDED"
    const val INTERNAL_ERROR = "INTERNAL_ERROR"

    /**
     * 例外の実クラスからエラーコードへの1対1写像（複数のクラスが同一コードに写ることはある）。
     * 全対象クラスは`final`（サブクラスを持たない、各クラス定義参照）であるため、
     * `throwable::class`の完全一致で元の`is`チェーンと同じ判定結果になる。
     */
    private val CODE_BY_EXCEPTION_TYPE: Map<KClass<out Throwable>, String> =
        mapOf(
            PromptVersionNotFoundException::class to PROMPT_NOT_FOUND,
            TemplateReferenceNotFoundException::class to TEMPLATE_NOT_FOUND,
            CircularDependencyException::class to CIRCULAR_DEPENDENCY,
            MacroRecursionException::class to CIRCULAR_DEPENDENCY,
            FragmentReferenceNotFoundException::class to FRAGMENT_NOT_FOUND,
            VariableUnresolvedException::class to VARIABLE_UNRESOLVED,
            ContextUnavailableException::class to CONTEXT_UNAVAILABLE,
            DraftReferenceNotAllowedException::class to VALIDATION_FAILED,
            PromptVersionStateNotAllowedException::class to VALIDATION_FAILED,
            ValidationFailedException::class to VALIDATION_FAILED,
            TokenBudgetExceededException::class to TOKEN_BUDGET_EXCEEDED,
            RenderFailedException::class to RENDER_ERROR,
            ExecutionFailedException::class to EXECUTION_FAILED,
            ParseFailedException::class to PARSE_FAILED,
            NestedPromptNotSupportedException::class to INVALID_REQUEST,
            InvalidPipelineRequestException::class to INVALID_REQUEST,
            CompositionDepthExceededException::class to COMPOSITION_LIMIT_EXCEEDED,
            CompositionSizeExceededException::class to COMPOSITION_LIMIT_EXCEEDED,
        )

    fun errorCodeFor(throwable: Throwable): String = CODE_BY_EXCEPTION_TYPE[throwable::class] ?: INTERNAL_ERROR
}
