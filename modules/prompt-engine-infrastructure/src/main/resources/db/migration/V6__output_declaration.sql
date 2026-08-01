-- V6__output_declaration.sql
-- ADR-0015: DSL output: 宣言（PromptVersion.output: OutputDeclaration）の永続化。
--
-- P2〜P8時点でこのテーブルは本番データを持たない（開発中）ため、既存行への
-- デフォルト値バックフィルは不要。NULL は「output: ブロック自体が宣言されていない」
-- として読み出し側（EventStorePromptRepository）が解釈する。

ALTER TABLE prompt_versions ADD COLUMN output JSON;
