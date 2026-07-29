-- V5__validation_settings.sql
-- ADR-0012: DSL validation: 宣言（PromptVersion.validation: ValidationSettings）の永続化。
--
-- P2〜P4時点でこのテーブルは本番データを持たない（開発中）ため、既存行への
-- デフォルト値バックフィルは不要。NULL は「省略時のValidationSettings既定値
-- （無制限・lenient）」として読み出し側（EventStorePromptRepository）が解釈する。

ALTER TABLE prompt_versions ADD COLUMN validation JSON;
