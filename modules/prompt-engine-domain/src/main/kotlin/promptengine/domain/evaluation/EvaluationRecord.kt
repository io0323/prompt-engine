package promptengine.domain.evaluation

import promptengine.domain.shared.SemVer
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * `evaluation_records`（設計書§12）の1行に対応する評価結果（ADR-0026決定3）。
 *
 * [eventId]は算出元の`PromptExecuted`の`event_id`。`(event_id, metric_type)`が
 * `evaluation_records`の冪等キーであり（V13、ADR-0025決定8をこのテーブルの粒度へ適用）、
 * 同一イベントの再配信で行が二重にならないことを保証する。
 *
 * `evaluation_records.version_id`はPrompt Versionの永続化サロゲートキー（UUID）だが、
 * イベントは業務キー（[promptKey] + [semVer]）しか運ばない。サロゲートキーへの解決は
 * `prompt-engine-infrastructure`のRepository実装が書き込み時に行う（`domain_events`・
 * `audit_logs`の`aggregate_id`と同じ方針、V1マイグレーションのコメント参照）。
 */
data class EvaluationRecord(
    val eventId: UUID,
    val promptKey: String,
    val semVer: SemVer,
    val metricType: String,
    val score: BigDecimal,
    val method: String,
    val sampleRef: String?,
    val evaluatedAt: Instant,
    val variantId: UUID? = null,
) {
    init {
        require(promptKey.isNotBlank()) { "promptKey must not be blank" }
        require(metricType.isNotBlank()) { "metricType must not be blank" }
        require(method.isNotBlank()) { "method must not be blank" }
    }
}
