package promptengine.domain.composition

/**
 * 参照解決のモード（設計書§2.6実行モード・§2.10 DependencyValidation）。
 *
 * [COMPILE_ONLY] のみDraft状態の参照を候補に含める。それ以外
 * （Render-only/Full-execution、設計書§2.6の(a)(b)）は挙動が同じため、
 * パイプライン全体の実行モードをそのまま持ち込まず[STANDARD]の1値にまとめる
 * （CompositionServiceが関心を持つのはCompile-onlyかどうかだけであり、
 * Render-onlyとFull-executionの違いはP8以降のPipeline Orchestratorの責務）。
 */
enum class CompositionMode {
    STANDARD,
    COMPILE_ONLY,
}
