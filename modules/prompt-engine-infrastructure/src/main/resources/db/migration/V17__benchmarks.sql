-- ADR-0035（Benchmarkフェーズ(b)）: Benchmark Aggregateの永続化。
-- Experiment（experiments/variants）とは無関係の独立したAggregateであるため、
-- 既存テーブルには一切変更を加えない。
-- benchmark_item_results（フェーズ(c)、非同期実行のClaim/フェンシング用）はここでは
-- 作成しない。実際に使われる時点のマイグレーションで追加する。
CREATE TABLE benchmarks (
    benchmark_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    prompt_id UUID NOT NULL REFERENCES prompts (prompt_id),
    dataset_id UUID NOT NULL REFERENCES golden_datasets (dataset_id),
    n_repetitions INTEGER NOT NULL,
    status VARCHAR NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ
);

-- variantsと異なりweight_pctを持たない（ADR-0035決定1・6）。
CREATE TABLE benchmark_targets (
    target_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    benchmark_id UUID NOT NULL REFERENCES benchmarks (benchmark_id),
    version_id UUID NOT NULL REFERENCES prompt_versions (version_id)
);

CREATE TABLE benchmark_metrics (
    benchmark_id UUID NOT NULL REFERENCES benchmarks (benchmark_id),
    metric_type VARCHAR NOT NULL,
    PRIMARY KEY (benchmark_id, metric_type)
);
