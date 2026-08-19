-- ADR-0035（Benchmarkフェーズ(c)）: 項目単位のClaim/フェンシングによる非同期実行の作業単位。
-- target_id × item_idの組ごとに1行（UNIQUE制約）。claimed_at/claimed_byはevent_bus_outbox
-- （V11/V12）と同じ3段階Claimパターン（Claim→実行（トランザクション外）→フェンシング付き確定）
-- で使う（決定3）。
CREATE TABLE benchmark_item_results (
    result_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    target_id UUID NOT NULL REFERENCES benchmark_targets (target_id),
    item_id UUID NOT NULL REFERENCES golden_dataset_items (item_id),
    status VARCHAR NOT NULL,
    claimed_at TIMESTAMPTZ,
    claimed_by VARCHAR,
    accuracy_score DECIMAL,
    consistency_score DECIMAL,
    determinism_score DECIMAL,
    error_message VARCHAR,
    completed_at TIMESTAMPTZ,
    UNIQUE (target_id, item_id)
);
