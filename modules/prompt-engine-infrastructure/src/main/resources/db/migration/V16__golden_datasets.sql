-- ADR-0035（Benchmarkフェーズ(a)）: Golden Datasetの永続化。
-- Experiment（experiments/variants）とは無関係の独立したAggregateであるため、
-- 既存テーブルには一切変更を加えない。
CREATE TABLE golden_datasets (
    dataset_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    prompt_id UUID NOT NULL REFERENCES prompts (prompt_id),
    name VARCHAR NOT NULL,
    description VARCHAR,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- expected_outputはnullable: Accuracy算出時のみ実質必須。Consistency/Determinismは
-- 期待出力を必要としない（ADR-0035決定2）。
-- positionはitems内での表示順を保持する（挿入順はDELETE+再INSERTで失われるため）。
CREATE TABLE golden_dataset_items (
    item_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dataset_id UUID NOT NULL REFERENCES golden_datasets (dataset_id),
    position INTEGER NOT NULL,
    parameters JSONB NOT NULL,
    context JSONB NOT NULL,
    expected_output TEXT,
    metadata JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (dataset_id, position)
);
