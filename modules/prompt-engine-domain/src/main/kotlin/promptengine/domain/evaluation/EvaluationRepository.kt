package promptengine.domain.evaluation

/**
 * 評価結果（`evaluation_records`、設計書§12）の追記専用ストア（ADR-0026決定3）。
 */
interface EvaluationRepository {
    /**
     * [records]を追記し、実際に挿入された件数を返す。
     *
     * 実装は`(event_id, metric_type)`の一意制約に対する`ON CONFLICT DO NOTHING`で書く契約
     * （ADR-0025決定8）。同一イベントの再配信では0件が返り、例外にはならない。
     */
    fun saveAll(records: List<EvaluationRecord>): Int
}
