package promptengine.domain.optimization

import promptengine.domain.shared.TokenCount

/**
 * Compressionが切り詰めたスコープ1件の記録（設計書§2.11、ADR-0013決定3）。
 *
 * [summary]は件数・トークン数などのメタ情報のみとし、切り詰めた実際のテキスト内容を
 * 含めてはならない（そのままAuditへ出力されるため、機密混入を避ける）。
 */
data class TruncationNote(
    val scope: String,
    val originalTokenEstimate: TokenCount,
    val truncatedTokenEstimate: TokenCount,
    val summary: String,
) {
    init {
        require(scope.isNotBlank()) { "scope must not be blank" }
        require(truncatedTokenEstimate.value <= originalTokenEstimate.value) {
            "truncatedTokenEstimate must not exceed originalTokenEstimate"
        }
    }
}
