-- prompt_aliases.upsert（PromptAliasRepository、P8バグ修正）が
-- ON CONFLICT (prompt_id, alias) を使えるようにする一意制約。
-- V1__init.sql時点ではこの制約が無く、同一alias名の複数行が作成できてしまっていた。
ALTER TABLE prompt_aliases
    ADD CONSTRAINT uq_prompt_aliases_prompt_alias UNIQUE (prompt_id, alias);
