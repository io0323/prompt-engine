package promptengine.domain.audit

/**
 * [AuditRecord]が記録するPipeline実行全体の成否（ADR-0015決定7）。
 */
sealed interface AuditOutcome {
    /** 全ステージが成功した。 */
    data object Success : AuditOutcome

    /** [errorCode]（設計書§13.3のエラーコード）でPipelineが打ち切られた。 */
    data class Failure(val errorCode: String) : AuditOutcome
}
