-- V3__extends_version_range.sql
-- ADR-0009: extends参照をkey + Version範囲の両方で保持する（ADR-0008決定1の追記改訂）。
--
-- CompositionServiceがextendsのVersion範囲（`@^2`等）を解決するには、
-- TemplateVersion.extendsKeyのようなkeyのみの構造化フィールドでは情報が足りず、
-- 所有者のDSLソースを実行時に再パースする必要が生じることが判明したため、
-- range文字列を保持する列を追加する。
--
-- prompt_versionsはP1/P2から実データを持つ表のため、DROP+CREATEではなくALTER TABLE
-- ADD COLUMNで追加する（template_versionsと異なりPromptのextendsは今回が初導入）。

ALTER TABLE template_versions ADD COLUMN extends_version_range VARCHAR;

ALTER TABLE prompt_versions ADD COLUMN extends_key VARCHAR;
ALTER TABLE prompt_versions ADD COLUMN extends_version_range VARCHAR;
