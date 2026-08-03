-- dependencies.to_versionをNULL許容にする（P9b）。
-- extends: <key>（Version範囲省略、VersionRange.Latest）はtoRangeText()がnullを返す仕様であり、
-- NOT NULL制約のままだと「latest」等のダミー文字列を書くことになりVersionRange.parseと
-- 往復しなくなる（ADR-0009「保存された参照 == ソースをパースした結果」の保証が崩れる）。
ALTER TABLE dependencies ALTER COLUMN to_version DROP NOT NULL;
