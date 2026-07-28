-- V2__template_fragment.sql
-- P3b: Template / Fragment Aggregate 永続化（ADR-0008）。
--
-- V1のtemplates/fragmentsは「他フェーズ用にスキーマのみ用意」のプレースホルダ
-- （V1コメント参照）であり実データを持たないため、破壊的な変更で安全に構造変更できる。
--
-- §4.3の不変条件（Template: 循環継承禁止、Fragment: 循環Include禁止）と、
-- §15.4のSemVer範囲Import仕様（`fragments/safety-policy@^2` = 同一キーの複数
-- Published Versionが同時に存在しうる）を満たすため、Prompt/PromptVersionと
-- 同じ「ヘッダ行 + Version子行」の形に変更する（ADR-0008）。

DROP TABLE fragments;
DROP TABLE templates;

CREATE TABLE templates (
    template_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    template_key VARCHAR NOT NULL UNIQUE,
    row_version BIGINT NOT NULL DEFAULT 0, -- 楽観ロック用（ADR-0008、promptsのrow_versionと同じ意図）
    created_by VARCHAR NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE template_versions (
    version_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    template_id UUID NOT NULL REFERENCES templates (template_id),
    version VARCHAR NOT NULL, -- SemVer
    body TEXT NOT NULL, -- DSL
    content_hash CHAR(64) NOT NULL,
    status VARCHAR NOT NULL, -- Draft/Published/Archived（PublicationState、ADR-0008）
    extends_key VARCHAR, -- extends先のTemplateKey。Version範囲の解決は3cスコープ（ADR-0008）
    created_by VARCHAR NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (template_id, version)
);

CREATE TABLE template_variable_defs (
    variable_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version_id UUID NOT NULL REFERENCES template_versions (version_id),
    name VARCHAR NOT NULL,
    type VARCHAR NOT NULL,
    required BOOLEAN NOT NULL,
    default_value TEXT,
    constraints JSON,
    sensitive BOOLEAN NOT NULL
);

CREATE INDEX idx_template_variable_defs_version_id ON template_variable_defs (version_id);

CREATE TABLE fragments (
    fragment_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    fragment_key VARCHAR NOT NULL UNIQUE,
    row_version BIGINT NOT NULL DEFAULT 0, -- 楽観ロック用（ADR-0008）
    created_by VARCHAR NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE fragment_versions (
    version_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    fragment_id UUID NOT NULL REFERENCES fragments (fragment_id),
    version VARCHAR NOT NULL, -- SemVer
    body TEXT NOT NULL, -- DSL
    content_hash CHAR(64) NOT NULL,
    status VARCHAR NOT NULL, -- Draft/Published/Archived（PublicationState、ADR-0008）
    created_by VARCHAR NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (fragment_id, version)
);

CREATE TABLE fragment_variable_defs (
    variable_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version_id UUID NOT NULL REFERENCES fragment_versions (version_id),
    name VARCHAR NOT NULL,
    type VARCHAR NOT NULL,
    required BOOLEAN NOT NULL,
    default_value TEXT,
    constraints JSON,
    sensitive BOOLEAN NOT NULL
);

CREATE INDEX idx_fragment_variable_defs_version_id ON fragment_variable_defs (version_id);
