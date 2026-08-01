-- JdbcPromptMetadataRepository.findOrCreateCategory（9a）がON CONFLICT (name)を使えるように
-- する一意制約（CodeRabbitレビュー指摘: SELECT-then-INSERTは並行upsertで同名categoryの
-- 重複行を作りうる）。tags.nameはV1__init.sql時点で既にUNIQUEだが、categories.nameには
-- 制約が無かったため、V7__prompt_aliases_unique_constraint.sqlと同じ方針で重複を解消してから
-- 制約を追加する。重複グループのうち最後の物理行（ctid最大）を残し、他の行を参照している
-- prompts.category_id / categories.parent_id（現行コードはparent_idを書かないため通常はNULLのみ
-- だが、将来のカテゴリ階層機能に備えて防御的に張り替える）は残す行へ張り替える。
UPDATE categories child
SET parent_id = keep.category_id
FROM categories dup
JOIN (
    SELECT DISTINCT ON (name) category_id, name
    FROM categories
    ORDER BY name, ctid DESC
) keep ON keep.name = dup.name
WHERE child.parent_id = dup.category_id
    AND dup.category_id <> keep.category_id;

UPDATE prompts p
SET category_id = keep.category_id
FROM categories dup
JOIN (
    SELECT DISTINCT ON (name) category_id, name
    FROM categories
    ORDER BY name, ctid DESC
) keep ON keep.name = dup.name
WHERE p.category_id = dup.category_id
    AND dup.category_id <> keep.category_id;

DELETE FROM categories dup
USING (
    SELECT DISTINCT ON (name) category_id, name
    FROM categories
    ORDER BY name, ctid DESC
) keep
WHERE dup.name = keep.name
    AND dup.category_id <> keep.category_id;

ALTER TABLE categories ADD CONSTRAINT uq_categories_name UNIQUE (name);
