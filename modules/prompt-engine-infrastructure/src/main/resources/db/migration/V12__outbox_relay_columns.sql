-- outbox（V1、ADR-0006）へ event_bus_outbox（V11）と同じクレーム/リトライ用の列を追加する
-- （ADR-0025決定2）。domain_eventsとのJOINで封筒データを取得する既存の設計は変更しない。
-- これにより単一のOutboxRelayerがoutbox/event_bus_outboxの両方を同じクレーム/ディスパッチ
-- 機構でドレインできる（OutboxSource抽象、prompt-engine-infrastructure）。
ALTER TABLE outbox
    ADD COLUMN claimed_at TIMESTAMPTZ,
    ADD COLUMN claimed_by VARCHAR,
    ADD COLUMN attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE INDEX idx_outbox_pending ON outbox (next_attempt_at) WHERE dispatched_at IS NULL;
