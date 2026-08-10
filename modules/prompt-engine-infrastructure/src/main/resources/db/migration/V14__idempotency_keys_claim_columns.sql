-- Issue #50: executeLongRunning予約行がプロセスクラッシュ（OOM Kill・ノード障害等）で
-- IN_PROGRESSのまま永久に残り、同一idempotencyKeyへの以降の全リクエストが
-- IdempotencyKeyInProgressExceptionで永久にブロックされる問題への対応（ADR-0027）。
--
-- ADR-0025のOutbox中継が使う「クレーム所有トークン＋タイムアウトベースの期限切れ判定＋
-- 書き戻し時のフェンシング」という3段階Claim方式（同ADR §3）を、idempotency_keysにも
-- 適用する。Outboxはバックグラウンドポーラーがキューを能動的に排出する必要があるのに対し、
-- idempotency_keysは「同一キーでの再送」が起きたときにだけ意味を持つ（排出すべきキューが
-- 無い）ため、バックグラウンドスイーパーは追加しない。代わりに、同一キーでの次回リクエスト
-- （JdbcIdempotentCommandExecutor.reserveOrResolve）がinlineで期限切れ予約を奪取する
-- （trigger機構の違いのみで、Claim/Fencingの3段階自体はADR-0025と同型）。
ALTER TABLE idempotency_keys ADD COLUMN claimed_at TIMESTAMPTZ;
ALTER TABLE idempotency_keys ADD COLUMN claimed_by VARCHAR;

-- このマイグレーション以前からIN_PROGRESSのまま滞留している行は、まさにIssue #50が指す
-- 「クラッシュで解放されなかった予約」そのものである。claimed_atを now() ではなく
-- created_at（本来の予約時刻）にバックフィルする。now()にすると、既にとっくに期限切れの
-- はずの予約に対して、このマイグレーション適用時点を起点とした新しいタイムアウト猶予を
-- 不当に与えてしまう（滞留が長いほど再奪取が遅れるという逆転が起きる）。
UPDATE idempotency_keys SET claimed_at = created_at, claimed_by = 'pre-p10c-migration' WHERE status = 'IN_PROGRESS';
