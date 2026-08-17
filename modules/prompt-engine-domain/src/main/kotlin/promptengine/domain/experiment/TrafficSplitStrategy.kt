package promptengine.domain.experiment

/**
 * Variant割当戦略（設計書§16拡張ポイント#12、§3.5 Strategyパターン、ADR-0034）。
 *
 * 既定実装（`prompt-engine-core`の`TrafficSplitStrategyImpl`）は重み付きランダム+sticky
 * （SHA-256ベースの安定ハッシュ、ADR-0034決定3、KDoc必読）。差替例として多腕バンディット等
 * （設計書§16）。
 *
 * [select]は[experiment]の`variants`から1件を返す（`variants`が空/1件のケースは
 * [Experiment.create]の不変条件により発生しない）。
 */
interface TrafficSplitStrategy {
    /**
     * @param experiment 対象Experiment（`variants`・`trafficPolicy`を参照する）
     * @param stickyKeyValue [TrafficPolicy.stickyKeyPath]で解決した値。パス未設定または
     *   呼出元のリクエストに対応する値が無ければ`null`（重み付き純粋ランダムへフォールバック）
     */
    fun select(
        experiment: Experiment,
        stickyKeyValue: String?,
    ): Variant
}
