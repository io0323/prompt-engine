-- V1__init.sql
-- 設計書§12（ER図）を基に、全テーブルを作成する（ADR-0006で追加した
-- prompts.row_version / outbox / prompt_snapshots を含む）。
--
-- P2時点で PromptRepository が実際に読み書きするのは
-- prompts / prompt_versions / domain_events / outbox / prompt_snapshots のみ。
-- 他テーブル（templates, fragments, variable_defs, dependencies, tags,
-- prompt_tags, categories, review_cases, approvals, experiments, variants,
-- evaluation_records, execution_logs, audit_logs）は後続フェーズ・他Aggregateの
-- ためにスキーマのみここで用意する。
--
-- audit_logs は追記専用ログである（設計書§12コメント「Secretマスク済」、
-- CLAUDE.md「ログは構造化」の精神を永続化層にも適用）。本マイグレーションでは
-- アプリケーションDBユーザーへのUPDATE/DELETE権限のREVOKEまでは行わない
-- （運用環境のDBロール設計に依存するため）。運用環境では、このテーブルに対して
-- UPDATE/DELETEを禁止するロール（例: INSERT/SELECTのみを許可する専用ロール）を
-- 別途用意し、アプリケーションはそのロールで接続することを前提とする。

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE categories (
    category_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR NOT NULL,
    parent_id UUID REFERENCES categories (category_id)
);

CREATE TABLE prompts (
    prompt_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    prompt_key VARCHAR NOT NULL UNIQUE,
    name VARCHAR NOT NULL,
    category_id UUID REFERENCES categories (category_id),
    description TEXT,
    state VARCHAR NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0, -- 楽観ロック用（ADR-0006）
    created_by VARCHAR NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE prompt_versions (
    version_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    prompt_id UUID NOT NULL REFERENCES prompts (prompt_id),
    version VARCHAR NOT NULL, -- SemVer
    content TEXT NOT NULL, -- DSL
    content_hash CHAR(64) NOT NULL,
    status VARCHAR NOT NULL,
    change_note TEXT,
    context_requirement JSON, -- PromptVersion.contextRequirement（ER図に列なし、ADR-0006で追加）
    created_by VARCHAR NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (prompt_id, version)
);

CREATE TABLE prompt_aliases (
    alias_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    prompt_id UUID NOT NULL REFERENCES prompts (prompt_id),
    alias VARCHAR NOT NULL, -- stable/canary
    version_id UUID NOT NULL REFERENCES prompt_versions (version_id)
);

CREATE TABLE templates (
    template_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    template_key VARCHAR NOT NULL UNIQUE,
    body TEXT NOT NULL,
    version VARCHAR NOT NULL,
    extends_key VARCHAR
);

CREATE TABLE fragments (
    fragment_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    fragment_key VARCHAR NOT NULL UNIQUE,
    body TEXT NOT NULL,
    version VARCHAR NOT NULL
);

CREATE TABLE variable_defs (
    variable_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version_id UUID NOT NULL REFERENCES prompt_versions (version_id),
    name VARCHAR NOT NULL,
    type VARCHAR NOT NULL,
    required BOOLEAN NOT NULL,
    default_value TEXT,
    constraints JSON,
    sensitive BOOLEAN NOT NULL
);

CREATE TABLE dependencies (
    dependency_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    from_version_id UUID NOT NULL REFERENCES prompt_versions (version_id),
    to_kind VARCHAR NOT NULL, -- template/fragment/prompt
    to_key VARCHAR NOT NULL,
    to_version VARCHAR NOT NULL
);

CREATE TABLE tags (
    tag_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR NOT NULL UNIQUE
);

CREATE TABLE prompt_tags (
    prompt_id UUID NOT NULL REFERENCES prompts (prompt_id),
    tag_id UUID NOT NULL REFERENCES tags (tag_id),
    PRIMARY KEY (prompt_id, tag_id)
);

CREATE TABLE review_cases (
    review_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version_id UUID NOT NULL REFERENCES prompt_versions (version_id),
    status VARCHAR NOT NULL,
    required_approvals INTEGER NOT NULL
);

CREATE TABLE approvals (
    approval_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    review_id UUID NOT NULL REFERENCES review_cases (review_id),
    approver VARCHAR NOT NULL,
    decision VARCHAR NOT NULL,
    comment TEXT,
    decided_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE experiments (
    experiment_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    prompt_id UUID NOT NULL REFERENCES prompts (prompt_id),
    type VARCHAR NOT NULL, -- AB/CANARY/BENCHMARK
    status VARCHAR NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ,
    winner_variant_id UUID -- ER図に<<FK>>指定なし（variantsとの循環参照を避ける）
);

CREATE TABLE variants (
    variant_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    experiment_id UUID NOT NULL REFERENCES experiments (experiment_id),
    version_id UUID NOT NULL REFERENCES prompt_versions (version_id),
    name VARCHAR NOT NULL,
    weight_pct INTEGER NOT NULL
);

CREATE TABLE evaluation_records (
    evaluation_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version_id UUID NOT NULL REFERENCES prompt_versions (version_id),
    variant_id UUID REFERENCES variants (variant_id),
    metric_type VARCHAR NOT NULL,
    score DECIMAL NOT NULL,
    method VARCHAR NOT NULL,
    sample_ref VARCHAR,
    evaluated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE execution_logs (
    execution_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version_id UUID NOT NULL REFERENCES prompt_versions (version_id),
    caller_system VARCHAR NOT NULL,
    trace_id VARCHAR NOT NULL,
    latency_ms INTEGER NOT NULL,
    input_tokens INTEGER NOT NULL,
    output_tokens INTEGER NOT NULL,
    cost DECIMAL NOT NULL,
    status VARCHAR NOT NULL,
    executed_at TIMESTAMPTZ NOT NULL
);

-- audit_logs: 追記専用（このテーブルへのUPDATE/DELETEはDBロールで禁止する運用を想定。
-- 上部コメント参照）。aggregate_type/aggregate_idはPrompt以外のAggregate
-- （ReviewCase、Experiment等）も指すため、特定テーブルへのFKは設定しない。
CREATE TABLE audit_logs (
    audit_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR NOT NULL,
    aggregate_id UUID NOT NULL,
    action VARCHAR NOT NULL,
    actor VARCHAR NOT NULL,
    payload JSON NOT NULL, -- Secretマスク済
    trace_id VARCHAR NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL
);

-- domain_events: Event Store（追記専用）。aggregate_id は Aggregate Root の
-- 永続化層サロゲートキー（例: prompts.prompt_id）。Domain層の
-- DomainEvent.aggregateId（PromptKeyの文字列値等、ビジネスキー）とは別物であり、
-- 永続化層でビジネスキー→サロゲートキーの解決を行う（ADR-0006）。
CREATE TABLE domain_events (
    event_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_id UUID NOT NULL,
    sequence BIGINT NOT NULL,
    event_type VARCHAR NOT NULL,
    payload JSON NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    UNIQUE (aggregate_id, sequence)
);

CREATE INDEX idx_domain_events_occurred_at ON domain_events (occurred_at);

-- outbox: domain_eventsへの追記と同一トランザクションで書く。Broker中継の
-- 実配線（ポーリング/プロデューサ）はP2の対象外（ADR-0006）。
CREATE TABLE outbox (
    outbox_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id UUID NOT NULL REFERENCES domain_events (event_id),
    dispatched_at TIMESTAMPTZ, -- NULL = 未配信
    created_at TIMESTAMPTZ NOT NULL
);

-- prompt_snapshots: Event Storeのスナップショット。監査・障害復旧用の代替復元経路
-- （通常のfindByKeyはRDB投影を使う。ADR-0006）。
CREATE TABLE prompt_snapshots (
    snapshot_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_id UUID NOT NULL REFERENCES prompts (prompt_id),
    sequence BIGINT NOT NULL, -- 保存時点の domain_events.sequence 最大値
    state JSONB NOT NULL, -- 直列化された集約状態（復元用）
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (aggregate_id, sequence)
);

CREATE INDEX idx_execution_logs_trace_id ON execution_logs (trace_id);
CREATE INDEX idx_audit_logs_occurred_at ON audit_logs (occurred_at);
