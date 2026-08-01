package promptengine.domain.render

/**
 * Renderingが失敗した（設計書§13.3 `RENDER_ERROR`、ADR-0015決定4・決定5）。
 *
 * Validationステージ（6）を通過した時点で、束縛済みAST・呼出パラメータ・宣言済みContextは
 * すべて検証済みである。その後段のRendering（8）が失敗するのは、未登録の`OutputFormatter`の
 * ようにEngine/Plugin側の構成不備・実装不具合に起因するケースに限られ、クライアント起因では
 * ないためサーバ起因（5xx）として扱う。
 *
 * `StageErrorMapper`はこの専用例外型のみを`RENDER_ERROR`へ写像する。ステージ自身の
 * `checkNotNull`（前段ステージ未実行の防御コード）が投げる汎用`IllegalStateException`は
 * この型ではないため`INTERNAL_ERROR`にフォールバックし、`RENDER_ERROR`と誤って混同しない
 * （ADR-0015決定4）。[reason]は構造的な理由のみを含む契約とする（生のprompt内容を含めない）。
 */
class RenderFailedException(
    val reason: String,
    cause: Throwable? = null,
) : RuntimeException("RENDER_ERROR: $reason", cause)
