package promptengine.domain.pipeline

/**
 * Pipelineの1ステージ（設計書§2.6・§3.4疑似コード`PipelineStage`）。
 *
 * 実装（`prompt-engine-application`、ADR-0015決定1）は既存のEngine（domain Interface）に
 * 委譲するだけの薄い層とする。失敗時はdomain例外を投げる（`PipelineOrchestrator`が
 * `StageErrorMapper`で設計書§13.3のエラーコードへ写像する、ADR-0015決定4）。
 */
interface PipelineStage {
    /** ログ・トレースSpan名に使うステージ名（例: `"Load"`）。設計書§2.6の表記と一致させる。 */
    val name: String

    /** [context]を受け取り、このステージの結果を反映した新しい[PipelineContext]を返す。 */
    fun execute(context: PipelineContext): PipelineContext
}
