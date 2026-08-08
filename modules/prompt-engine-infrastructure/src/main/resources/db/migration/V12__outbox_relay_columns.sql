-- outbox（V1、ADR-0006）へ event_bus_outbox（V11）と同じクレーム/リトライ用の列を追加する
-- （ADR-0025決定2）。domain_eventsとのJOINで封筒データを取得する既存の設計は変更しない。
-- これにより単一のOutboxRelayerがoutbox/event_bus_outboxの両方を同じクレーム/ディスパッチ
-- 機構でドレインできる（OutboxSource抽象、prompt-engine-infrastructure）。
ALTER TABLE outbox
    ADD COLUMN claimed_at TIMESTAMPTZ,
    ADD COLUMN claimed_by VARCHAR,
    ADD COLUMN attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now();

-- CREATE INDEX CONCURRENTLYは使わない（CodeRabbitレビュー指摘で検討済み、判断のうえ見送り）。
-- CONCURRENTLYはPostgreSQLの仕様上トランザクションブロック内で実行できないが、Flywayは
-- マイグレーションをデフォルトでトランザクション内実行する。本移行は用途上デプロイ時の
-- スキーマ変更（V11で作成した直後のevent_bus_outboxと同じタイミング）であり、outboxは
-- この時点でまだ空またはごく少量（本番稼働開始前）のため、通常のCREATE INDEXが取得する
-- 短時間のテーブルロックが実運用上の問題になるケースは想定されない。将来outboxが
-- 大きくなった状態でインデックスを追加し直す必要が生じた場合は、その時点で
-- Flywayのtransactional=false（`.sql`ファイル名ベースの設定）や別マイグレーション手段を検討する。
CREATE INDEX idx_outbox_pending ON outbox (next_attempt_at) WHERE dispatched_at IS NULL;
