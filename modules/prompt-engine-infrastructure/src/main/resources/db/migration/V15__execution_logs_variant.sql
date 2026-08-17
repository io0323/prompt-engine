-- Experiment Engine（FR-015、A/Bテスト・Canary、ADR-0034）。
--
-- evaluation_records.variant_idはV1__init.sqlで既に定義済み（Experiment未実装のため
-- 常にNULLだった）。execution_logsには対応する列が無く、設計書§12のER図も
-- evaluation_recordsにしかvariant_idを持たせていなかったが、運用者が実行ログから
-- 直接「どのVariantが使われたか」を追える方が「過去の実行を再現できること」
-- （設計書§2.14監査要件）に直接応えるため、ここで追加する（ADR-0034決定4）。
--
-- Experiment経由でない通常の実行はNULLのまま（既存行は全てNULL、後方互換）。
ALTER TABLE execution_logs ADD COLUMN variant_id UUID REFERENCES variants (variant_id);

-- TrafficPolicy（設計書§4.4「variant→重み(%)、sticky key（userId等）」）のうち、
-- 重み配分はvariants.weight_pctが既に持つ。sticky keyの参照パス（例 "user.id"、
-- PromptRequest.contextDataから読む経路、ADR-0034決定3）はExperiment単位の設定であり
-- 対応する列が無かったため追加する。未設定（NULL）は「sticky割当を行わず重み付き
-- 純粋ランダムのみ」を意味する。
ALTER TABLE experiments ADD COLUMN sticky_key_path VARCHAR;
