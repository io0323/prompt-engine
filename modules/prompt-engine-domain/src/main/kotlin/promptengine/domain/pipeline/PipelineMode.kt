package promptengine.domain.pipeline

/**
 * Pipeline実行モード（設計書§2.6「実行モード」、ADR-0015決定3）。
 *
 * `PipelineFactory`（`prompt-engine-application`、ADR-0015決定6）がこのモードに応じて
 * Stage 1〜12のうち実行するサブセットを選択する。
 */
enum class PipelineMode {
    /** Stage 1〜8。クライアントが自分で実行する（APAPへ委譲しない）。 */
    RENDER_ONLY,

    /** Stage 1〜12。PEがAPAP経由で実行委譲する。 */
    FULL_EXECUTION,

    /** Stage 1〜3 + 6。CI検証用。 */
    COMPILE_ONLY,
}
