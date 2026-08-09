-- event_bus_outbox: EventBusAdapter.publish()専用のOutbox（ADR-0025決定1）。
-- 既存の domain_events/outbox（ADR-0006、Prompt Aggregate Event Store）とは独立させる。
-- PromptExecutedEvent等Pipeline通知イベントのaggregateIdはビジネスキー文字列であり、
-- Prompt Aggregateの状態遷移でもないため、appendDomainEventsのPromptロック・sequence採番
-- ロジックを流用しない。topic列は持たず、中継時にevent_typeからEventTopicResolverで解決する
-- （冗長な導出データを持たないため）。
CREATE TABLE event_bus_outbox (
    outbox_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id UUID NOT NULL UNIQUE,
    event_type VARCHAR NOT NULL,
    aggregate_type VARCHAR NOT NULL,
    aggregate_id VARCHAR NOT NULL, -- 業務キー。domain_eventsと異なりUUID解決不要（ADR-0025）
    actor VARCHAR NOT NULL,
    trace_id VARCHAR NOT NULL,
    payload JSON NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    claimed_at TIMESTAMPTZ,
    claimed_by VARCHAR,
    dispatched_at TIMESTAMPTZ,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_event_bus_outbox_pending ON event_bus_outbox (next_attempt_at) WHERE dispatched_at IS NULL;
