-- P10b: 購読側の冪等性（ADR-0025決定8）と実DLQ（Issue #37）のためのスキーマ変更。
--
-- ADR-0025決定8は「各購読側が自分の書き込み先テーブルに event_id UNIQUE 制約を持ち、
-- INSERT ... ON CONFLICT (event_id) DO NOTHING で書く」ことを購読側冪等性の統一パターンと
-- 定めた。P10bで初めて実サブスクライバ（ExecutionLogSubscriber / EvaluationEngine /
-- AuditEngine）を追加するため、それぞれの書き込み先へ event_id 列を追加する。
-- テーブル自体（execution_logs / evaluation_records / audit_logs）はV1で既に存在するため
-- 作り直さず、列の追加のみを行う。

-- execution_logs: ExecutionLogSubscriber（PromptExecutedを購読）が唯一の書き込み経路。
-- P10b以前に書き込み経路が存在しなかったため本テーブルは空であり、NOT NULLを直接付けられる
-- （既存行が無いのでDEFAULTやバックフィルを必要としない）。1イベント＝1実行ログなので
-- event_id単独のUNIQUEが正しい冪等キーになる。
ALTER TABLE execution_logs
    ADD COLUMN event_id UUID NOT NULL;
ALTER TABLE execution_logs
    ADD CONSTRAINT uq_execution_logs_event_id UNIQUE (event_id);

-- execution_logs.version_id での「直近N日間に実行が無いこと」ガード（ArchiveHandler、
-- Issue #48）が使う索引。
CREATE INDEX idx_execution_logs_version_executed_at ON execution_logs (version_id, executed_at DESC);

-- evaluation_records: EvaluationEngineが1つのPromptExecutedから複数の評価器
-- （M1: Latency / TokenUsage / Cost）の結果を書くため、1イベントに対して複数行が正当に存在する。
-- したがって event_id 単独のUNIQUEではなく (event_id, metric_type) の複合UNIQUEを冪等キーとする
-- （ADR-0025決定8の「自分の書き込み先テーブルの冪等キー」を、このテーブルの粒度に合わせて
-- 解釈したもの。単独UNIQUEにすると同一イベントの2つ目以降の評価器の結果が
-- ON CONFLICT DO NOTHINGで恒久的に捨てられてしまう）。
ALTER TABLE evaluation_records
    ADD COLUMN event_id UUID NOT NULL;
ALTER TABLE evaluation_records
    ADD CONSTRAINT uq_evaluation_records_event_metric UNIQUE (event_id, metric_type);

-- audit_logs: AuditEngine（全6トピック購読）由来の行は event_id を持つが、既存のCRUD/lifecycle
-- ハンドラ由来の書き込み経路（AuditRepository.record()/AuditLogEntry、ADR-0017）と
-- Pipeline Stage 12（AuditRepository.append()/AuditRecord、ADR-0015決定7）は、キーにできる
-- イベントを持たない。このためNULL許容とし、UNIQUE制約はNULLを互いに異なる値として扱う
-- PostgreSQLの既定挙動（部分的な一意性）にそのまま乗せる。
ALTER TABLE audit_logs
    ADD COLUMN event_id UUID;
ALTER TABLE audit_logs
    ADD CONSTRAINT uq_audit_logs_event_id UNIQUE (event_id);

-- dead_letter_queue: 実DLQ（Issue #37、ADR-0026決定2）。Brokerの専用DLQトピックではなく
-- Postgresの専用テーブルとする。P10bで実際に配線するのはAudit書き込み失敗経路のみだが、
-- subscriber_name列で購読側を識別する汎用形にしておき、他のサブスクライバからも再利用できる
-- ようにする。
--
-- 再処理は手動（運用者が起動するコマンド/管理経路）を前提とし、自動リトライポーラは持たない
-- （ADR-0026決定2でM1スコープ外と明記）。
--
-- (event_id, subscriber_name) をUNIQUEにすることで、at-least-once配信で同じイベントが
-- 再配信され再び同じ購読側で失敗しても、DLQ行が際限なく増えず retry_count / last_failed_at の
-- 更新に集約される。event_idがNULLの行（Pipeline Stage 12のAuditRecord退避経路。この経路は
-- キーにできるイベントを持たない）は、PostgreSQLがNULLを互いに異なる値として扱うため
-- 失敗の都度1行ずつ積まれる（この経路では失敗発生回数そのものが記録として意味を持つ）。
CREATE TABLE dead_letter_queue (
    dlq_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id UUID, -- NULL可: Pipeline Stage 12のAuditRecord退避経路はイベントを持たない
    event_type VARCHAR NOT NULL,
    subscriber_name VARCHAR NOT NULL,
    payload JSON NOT NULL, -- Secretマスク済（退避前に必ずサニタイズする、ADR-0026決定4）
    failure_reason VARCHAR NOT NULL,
    first_failed_at TIMESTAMPTZ NOT NULL,
    last_failed_at TIMESTAMPTZ NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    status VARCHAR NOT NULL DEFAULT 'PENDING',
    UNIQUE (event_id, subscriber_name)
);

-- 退避の検知（PENDING件数のポーリング、ADR-0026決定2）が使う部分索引。
CREATE INDEX idx_dead_letter_queue_pending ON dead_letter_queue (first_failed_at) WHERE status = 'PENDING';
