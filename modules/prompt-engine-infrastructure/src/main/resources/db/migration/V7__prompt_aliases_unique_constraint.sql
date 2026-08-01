-- prompt_aliases.upsert（PromptAliasRepository、P8バグ修正）が
-- ON CONFLICT (prompt_id, alias) を使えるようにする一意制約。
-- V1__init.sql時点ではこの制約が無く、同一(prompt_id, alias)の複数行が作成できていた
-- （CodeRabbitレビュー指摘: 既存データに重複行があると制約追加自体が失敗する）。
-- 制約追加前に重複を解消する。alias_idはgen_random_uuid()採番のため時系列の意味を
-- 持たないが、ctid（物理行位置）は同一トランザクション内で安定した順序を持つため、
-- 「重複グループのうち最後の物理行を残す」という決定的な規則で1件に統合する。
DELETE FROM prompt_aliases pa
USING prompt_aliases pa_newer
WHERE pa.prompt_id = pa_newer.prompt_id
    AND pa.alias = pa_newer.alias
    AND pa.ctid < pa_newer.ctid;

-- 本テーブルは低頻度更新の小規模テーブル（Prompt 1件あたり数エイリアス程度）であり、
-- CREATE UNIQUE INDEX CONCURRENTLY + ALTER TABLE ... ADD CONSTRAINT ... USING INDEX
-- （ACCESS EXCLUSIVEロックを避ける定石）が要求する非トランザクション実行は、
-- Flywayの通常のトランザクション内マイグレーション運用との整合コストに見合わないと
-- 判断し、通常のADD CONSTRAINTのままとする（Squawk指摘への対応判断）。
ALTER TABLE prompt_aliases
    ADD CONSTRAINT uq_prompt_aliases_prompt_alias UNIQUE (prompt_id, alias);
