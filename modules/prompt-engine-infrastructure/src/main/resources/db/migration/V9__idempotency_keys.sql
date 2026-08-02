-- Idempotency-Key（設計書§13.1、全POST）の記録用テーブル（P9b、設計書§12）。
-- IdempotentCommandExecutorが読み書きする。result_json/result_typeはCOMPLETEDになるまでNULL。
CREATE TABLE idempotency_keys (
    idempotency_key VARCHAR PRIMARY KEY,
    request_fingerprint VARCHAR NOT NULL,
    status VARCHAR NOT NULL, -- IN_PROGRESS / COMPLETED
    result_type VARCHAR,
    result_json JSON,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ
);
