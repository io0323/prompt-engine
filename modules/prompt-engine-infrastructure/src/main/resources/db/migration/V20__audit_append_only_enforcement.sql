-- 監査ログ・Event StoreのDB層追記専用強制（Issue #85、ADR-0036）。
--
-- 本マイグレーションはprompt_engine_migrator（Flyway接続ロール、テーブル所有者）として
-- 実行される。アプリケーション実行時ロールprompt_engineは、この時点まで public スキーマの
-- いかなるテーブルにも権限を持たない。
--
-- 対象を audit_logs / domain_events の2テーブルに限定する理由: この2つは真に追記専用
-- （audit_logsは監査記録、domain_eventsはEvent Sourcingの正典）である。一方 outbox /
-- dead_letter_queue は claimed_by / dispatched_at 等の状態更新を設計上必要とする作業キュー
-- であり、追記専用にはできない（V1__init.sqlのコメント参照、両テーブルは元々「追記専用」と
-- 記述されていない）。

-- 0. prompt_engineロールが存在しなければ作成する（冪等）。ロール作成をこのマイグレーション
--    自体に持たせることで、docker-entrypoint-initdb.d等の環境固有の初期化スクリプトに
--    依存せず、Flywayを実行するあらゆる環境（compose/CI/Testcontainers）で同じ2ロール構成が
--    再現される。パスワードはローカル開発既定値であり、本番環境では
--    PE_DATASOURCE_PASSWORD（Helm Secret経由）で別途上書きされる運用を前提とする。
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'prompt_engine') THEN
        CREATE ROLE prompt_engine LOGIN PASSWORD 'prompt_engine';
    END IF;
END
$$;

-- 1. 既存の全テーブルへ、通常のCRUD権限を広く付与する
--    （audit_logs/domain_eventsを含む。3で個別に絞る）。
GRANT USAGE ON SCHEMA public TO prompt_engine;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO prompt_engine;

-- 2. 今後このマイグレーションを実行するロール（テーブル所有者）が作成するテーブルにも
--    同じ既定権限が及ぶようにする（既存テーブルへは効果を持たない。1が既存分、これが
--    将来分を担う）。FOR ROLEを省略すると対象は常にCURRENT_USER（=この接続のロール）になる
--    （PostgreSQL仕様）。ロール名を"prompt_engine_migrator"と決め打ちしないことで、
--    そのロール名を持たない環境（例: Testcontainersが既定で払い出す任意名のロールで
--    Flywayを実行するテスト）でも本マイグレーションが失敗しない。
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO prompt_engine;

-- 3. audit_logs / domain_events のみ、UPDATE/DELETEを剥がしINSERT/SELECTに絞る
--    （追記専用の実効的な強制。テーブル所有権はprompt_engine_migratorのままであり、
--    prompt_engineは自分自身に権限を再GRANTできない）。
REVOKE UPDATE, DELETE ON audit_logs FROM prompt_engine;
REVOKE UPDATE, DELETE ON domain_events FROM prompt_engine;
