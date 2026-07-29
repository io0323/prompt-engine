-- V4__variable_source_and_context_requirements.sql
-- ADR-0011: VariableDefinition.source の追加とContextRequirementの複数形化。
--
-- source列: 既存行を壊さないよう NOT NULL DEFAULT 'STATIC'（VariableSource.STATICと
-- 同じ既定値、VariableDefinition.source省略時のデフォルトと揃える）で追加する。
--
-- context_requirement→context_requirements: P2時点でこの列は本番データを持たない
-- （開発中）ため、値の移行は行わずRENAMEのみ行う。JSON型のまま単一オブジェクトから
-- 配列へ意味が変わるが、型自体（JSON）はどちらも受け付けるためカラム型の変更は不要。

ALTER TABLE variable_defs ADD COLUMN source VARCHAR NOT NULL DEFAULT 'STATIC';
ALTER TABLE template_variable_defs ADD COLUMN source VARCHAR NOT NULL DEFAULT 'STATIC';
ALTER TABLE fragment_variable_defs ADD COLUMN source VARCHAR NOT NULL DEFAULT 'STATIC';

ALTER TABLE prompt_versions RENAME COLUMN context_requirement TO context_requirements;
