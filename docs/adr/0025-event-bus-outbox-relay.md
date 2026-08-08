# ADR-0025: Event Bus 実装（Outbox → Broker中継）とTopic routing（P10a）

## ステータス

Accepted

## コンテキスト

ADR-0015決定7は`EventBusAdapter`（設計書§16拡張ポイント#14）の最小実装として
`InMemoryEventBusAdapter`を導入し、`production`プロファイルで選択された場合は
起動時エラーとする方針を決めた。これはIssue #35で本実装へ置き換える前提の
暫定実装であり、結果として`production`プロファイルは今日に至るまで一度も
起動できない（`AuditEventConfig.eventBusAdapter`に`@Profile`ガードが無く、
`InMemoryEventBusAdapter`を無条件に構築するため）。

一方、Prompt Aggregateの状態遷移はP2（ADR-0006）で確立した`domain_events` +
`outbox`（Aggregate Event Store）に書き込まれ続けているが、`outbox`をBrokerへ
中継する経路はP2時点で意図的にスコープ外とされたまま今日まで存在しない
（Issue #11）。

本ADRはP10（Issue #35・#11の回収、`docs/prompts/p10a.md`）の対象範囲として、
以下を確定する。

1. Outbox → Broker中継の実装形態（ポーリング/クレーム機構、リトライ・バックオフ、
   複数インスタンス下での二重配信防止）
2. `EventBusAdapter`の本番実装（`EvaluationStage`が発行する`PromptExecutedEvent`のような
   Pipeline通知イベント用の新しいOutbox）
3. イベント種別→Kafka互換Topicの解決（設計書§14の6トピック）
4. 購読側の冪等性パターン（本PRでは方針の確立のみ。実際のAudit/Evaluation
   Kafka Subscriberは10bのスコープ）
5. ローカル/CIでのBroker（Testcontainers Redpanda）

本ADRのスコープ外（10b/10cへ明示的に先送り）: 実DLQ、Audit/Evaluation Engineの
実Kafka Subscriber実装、Micrometer/OTelによる中継の計測。

## 決定

### 1. `PromptExecutedEvent`のようなPipeline通知イベント専用に、既存`outbox`とは別の`event_bus_outbox`を新設する

`appendDomainEvents`（`DomainEventAppender.kt`）が書く`domain_events`/`outbox`は
Prompt Aggregateの状態遷移のReplay/障害復旧専用のEvent Store（ADR-0006）であり、
`SELECT ... FOR UPDATE`によるPrompt行ロック＋Aggregate単位の`sequence`採番という
Prompt Aggregate固有の整合性機構を持つ。

`EvaluationStage`が発行する`PromptExecutedEvent`は
- `aggregateId`が`PromptKey.value`というビジネスキー文字列であり、`prompts.prompt_id`
  （UUID）への解決を要求すると本来不要な変換ステップが増える
- Prompt Aggregate自身の状態遷移イベントではなく、Pipeline Orchestratorが発火する
  通知イベント（設計書§14「発火元: Pipeline Orchestrator」）である

という2点で`domain_events`が前提とする形と一致しない。無理に`appendDomainEvents`へ
合流させると、Aggregate Event StoreのReplayストリームとPipeline通知イベントが
混在し、ADR-0006が確立した「`domain_events`はAggregate単位でリプレイ可能」という
不変条件を壊す。

このため、`EventBusAdapter.publish()`専用の独立したOutboxテーブル
`event_bus_outbox`を新設する（`V11__event_bus_outbox.sql`）。`domain_events`/`outbox`
のReplay/障害復旧責務には一切手を入れない。

```sql
CREATE TABLE event_bus_outbox (
    outbox_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id UUID NOT NULL UNIQUE,
    event_type VARCHAR NOT NULL,
    aggregate_type VARCHAR NOT NULL,
    aggregate_id VARCHAR NOT NULL, -- 業務キー。domain_eventsと異なりUUID解決不要（本ADR）
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
```

`topic`列は持たない。イベント種別からTopicへの解決は中継時に`EventTopicResolver`
（決定3）で行い、冗長な導出データをテーブルに持たせない。

### 2. 既存`outbox`にも同じクレーム/中継用の列を追加し、単一の中継エンジンで両方をドレインする

`event_bus_outbox`だけを中継しても、`domain_events`/`outbox`（Prompt状態遷移イベント、
設計書§14の`PromptCreated`/`PromptPublished`等）は依然としてBrokerへ届かない。
Issue #11は「Outbox → Broker中継」自体を指しており、既存`outbox`もスコープに含まれる。

既存`outbox`テーブルへ、`event_bus_outbox`と同じクレーム/リトライ用の列を追加する
（`V12__outbox_relay_columns.sql`）。

```sql
ALTER TABLE outbox
    ADD COLUMN claimed_at TIMESTAMPTZ,
    ADD COLUMN claimed_by VARCHAR,
    ADD COLUMN attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now();
CREATE INDEX idx_outbox_pending ON outbox (next_attempt_at) WHERE dispatched_at IS NULL;
```

これにより、1つの中継エンジン（`OutboxRelayer`、`prompt-engine-infrastructure`）を
`OutboxSource`という小さな抽象（`claimBatch`/`markDispatched`/`markFailed`）で
パラメータ化し、2つの具体実装

- `EventBusOutboxSource`: `event_bus_outbox`単独（自己完結、封筒の全列を持つ）
- `DomainEventOutboxSource`: 既存`outbox` JOIN `domain_events`（封筒は`domain_events`側の
  列から取得。`outbox`自体は依然として封筒データを持たない、ADR-0006の設計をそのまま維持）

を与えることで、同じクレーム/ディスパッチ/リトライのロジックを2重実装せずに済む。
`bootstrap`は2つの`OutboxRelayer`インスタンス（それぞれ異なる`OutboxSource`を注入）を
それぞれ独立した`@Scheduled`ジョブから駆動する。

### 3. クレーム機構: 3フェーズ（クレーム/配信/確定）でDBロックをネットワークI/Oの外に出す

Broker送信（ネットワークI/O）をDBトランザクションの中で行うと、ロック保持時間が
ネットワーク遅延に左右され、他インスタンスのクレームを不必要に長くブロックする。
このため中継処理を3フェーズへ分離する。

1. **クレーム（短いトランザクション）**: `SELECT ... FOR UPDATE SKIP LOCKED`で対象行を
   選び（`dispatched_at IS NULL AND next_attempt_at <= now() AND (claimed_at IS NULL
   OR claimed_at < now() - claimTimeout)`、`created_at`昇順、`LIMIT batchSize`）、
   `claimed_at = now()`・`claimed_by = :instanceId`で更新してからコミットする。
   `SKIP LOCKED`により、他インスタンスが同時に同じクレーム問い合わせを実行しても
   異なる行の集合を取得する（複数インスタンス下での二重クレーム防止）。
2. **配信（DBトランザクションを開かない）**: クレームした各行についてKafka Producerへ
   `producer.send(record).get(timeout)`で同期送信する（M1の想定ボリュームでは
   バッチ非同期化のオーバーヘッドを避け同期で十分という判断）。
3. **確定（行単位の短いトランザクション）**: 成功時は`dispatched_at = now()`。失敗時は
   `claimed_at = NULL`・`attempts = attempts + 1`・`next_attempt_at = now() + backoff`
   （`claimed_at`を解放することで、次のポーリングサイクルで即座に再クレーム対象になる）。

`claimed_by`（`hostname + "-" + UUID.randomUUID()`、プロセスごとに1回生成）は、
クレーム後に配信中でプロセスがクラッシュした行を検出するために使う。クラッシュした
インスタンスの`claimed_at`は更新されないまま残るため、`claimTimeout`（既定30秒、
`promptengine.eventbus.relay.claim-timeout-seconds`で設定可能）を過ぎると別インスタンスの
クレーム問い合わせに再び現れ、再クレームされる。**これが「中継の途中でプロセスが落ちても
イベントが失われない」ことを保証する機構**（テスト要件、`claimed_at`を過去に手動更新して
シミュレートする）。

**フェンシング（`markDispatched`/`markFailed`の所有権検証）は3段階Claim方式の正しさの
前提であり、省略できない。** クラッシュ再claim（前段落）は「claim_timeoutを過ぎたら別
インスタンスが同じ行を再クレームしてよい」という設計だが、これは同時に「元のインスタンス
（フェーズ2でBroker送信中に応答が遅れていただけで、実際にはクラッシュしていなかった場合を
含む）が、フェーズ3で自分の送信結果を確定しようとする時点では、その行の所有者がもう自分では
ない可能性がある」ことを意味する。フェンシングが無いと、元のインスタンスの`markDispatched`/
`markFailed`が`WHERE outbox_id = :outboxId`のみで無条件に成功してしまい、既に別インスタンスが
書き込んだ`claimed_at`/`claimed_by`/`attempts`/`next_attempt_at`を意図せず上書きする
（CodeRabbitレビュー指摘: 「クレームは一時点で1インスタンスにのみ属する」という前提が
壊れる）。このため`markDispatched(outboxId, instanceId)`/`markFailed(outboxId, instanceId,
nextAttemptAt)`のUPDATE文は`WHERE outbox_id = :outboxId AND claimed_by = :instanceId`を
条件とし、0行更新（＝別インスタンスに再claimされていた）の場合は`false`を返す。
`OutboxRelayer`は`false`が返った場合、黙って成功扱いにせず`outbox_relay_fencing_lost`を
SLF4J WARNログへ記録する（`outboxId`/`eventId`/`phase`/`instanceId`を構造化して残す）。
メトリクスへの反映はMonitoring実装（10cスコープ）に委ねる。なお、送信自体（フェーズ2）は
Brokerへは成功しているため、フェンシングを失っても`relayOnce()`の戻り値（配信件数）には
そのまま数える（Broker送信の成否と、DB側の確定の所有権は別の関心事のため）。

### 4. リトライ・バックオフ: 指数バックオフ（上限あり）、DLQは10bへ先送り

`attempts`回数に基づき、`base=1秒 * 2^(attempts-1)`、上限`5分`の指数バックオフで
`next_attempt_at`を進める。無限に再試行し続け、恒久的な失敗（例: Topic誤設定）を
自動検知して隔離する機構（DLQ）は持たない。これは`docs/prompts/p10a.md`のP10分割
方針で10bのスコープと明記されている（Issue #37「実DLQ」）ため、本PRでは意図的に
実装しない。運用上は`attempts`・`next_attempt_at`が異常に積み上がった行を
モニタリング（10cスコープ）で検知する想定。

### 5. ポーリング間隔・バッチサイズは`@ConfigurationProperties`で外出しする

```yaml
promptengine.eventbus.relay.poll-interval-ms: 750   # 既定
promptengine.eventbus.relay.batch-size: 50           # 既定
promptengine.eventbus.relay.claim-timeout-seconds: 30 # 既定
```

`OutboxRelayProperties`のコンストラクタ`init`ブロックで3値すべてが正であることを検証する
（0以下はOutboxRelayerを機能不全にする: `batchSize<=0`は毎回0件クレーム、
`claimTimeoutSeconds<=0`は自分がクレームした行を次のポーリングで即座に再クレーム対象に
してしまう。設定バインディング時点でfail-fastする、CodeRabbitレビュー指摘）。

`OutboxRelayProperties`（`prompt-engine-bootstrap`）にバインドする。中継エンジン
自体（`OutboxRelayer`、`prompt-engine-infrastructure`）はSpringの`@ConfigurationProperties`
を知らず、コンストラクタ引数として単純な値（`Duration`/`Int`）を受け取るのみとする
（infrastructureはSpring依存を持ってよいが、`@ConfigurationProperties`バインディングの
配線自体はDI結線としてbootstrapに閉じる、CLAUDE.md「具象クラスのDI結線はbootstrapの
Configurationクラスでのみ行う」）。

2つの`@Scheduled`ジョブ（`event_bus_outbox`用・既存`outbox`用）は、専用の
`ThreadPoolTaskScheduler`（プールサイズ2、`OutboxRelayConfig.outboxRelayTaskScheduler`）上で
実行する。Springの既定`TaskScheduler`はプールサイズ1であり、2ジョブが単一スレッドで
直列化されると片方のBroker送信遅延がもう片方のポーリングサイクルまで止めてしまう
（CodeRabbitレビュー指摘）。

`@Scheduled`ジョブおよび関連Bean（Kafka `Producer`、`OutboxRelayer`×2、
`ThreadPoolTaskScheduler`）は`@Profile("production")`のみで有効化する。既存の
`AuditRepository`/`EventBusAdapter`の
`production`/`!production`分岐（ADR-0015決定7）と同じ扱いとし、非productionプロファイルは
引き続き`InMemoryEventBusAdapter`を使い中継は起動しない。これは非productionで
既存`outbox`が中継されず溜まり続ける既存の挙動（ADR-0006時点から変化なし）を
本PRで変えないことを意味する（回帰ではなく、単に対象範囲外）。

### 6. `EventBusAdapter`の本番実装は「Outboxへ書くだけ」の`OutboxEventBusAdapter`とする

`EvaluationStage`（`prompt-engine-application`）は`EventBusAdapter.publish()`を
`runCatching`で包み、「本流を失敗させない」契約を持つ（ADR-0015決定7を継承）。
Kafkaへの同期送信をこの呼び出しの中で行うと、Broker接続不調がPipeline本流の
レイテンシに直結し、上記契約の精神（非同期評価のためのfire-and-forget）に反する。

`OutboxEventBusAdapter`（`prompt-engine-infrastructure`、`infrastructure.messaging`）は
`publish(event)`の中で`event_bus_outbox`へ1行INSERTするだけの薄い実装とし、Kafkaとの
通信は一切行わない。`EvaluationStage`の呼出箇所自体は既存のトランザクションを開いていない
ため、`OutboxEventBusAdapter`は自前で短いトランザクション（`TransactionTemplate`）を
開いてINSERTする。実際のBroker送信は決定1〜3の`OutboxRelayer`が非同期に行う。

（名前について: `KafkaEventBusAdapter`という命名も検討したが、このクラス自体は
Kafkaクライアントに一切依存しないため、実体を表す`OutboxEventBusAdapter`を採用した。
Kafka Producerへの依存は`OutboxRelayer`/`KafkaEventProducer`側に閉じる。）

### 7. Topic Routing: `EventTopic`（enum）+ `EventTopicResolver`を`prompt-engine-domain`に置く

Topicの集合（設計書§14の6トピック）は「イベント種別からどのTopicへ送るか」という
純粋な語彙・ルーティングテーブルであり、Kafka等の技術選定に依存しない。ArchUnitの
「domainは他モジュール・フレームワークに依存しない」規約と両立させるため、
`domain.event`パッケージにフレームワーク非依存のenumと解決関数を置く。

```kotlin
enum class EventTopic(val topicName: String) {
    PE_PROMPT("pe.prompt"),
    PE_EXECUTION("pe.execution"),
    PE_EVALUATION("pe.evaluation"),
    PE_EXPERIMENT("pe.experiment"),
    PE_GOVERNANCE("pe.governance"),
    PE_PLUGIN("pe.plugin"),
}

object EventTopicResolver {
    fun resolve(eventType: String): EventTopic
}
```

設計書§14の全イベント名（30件）を以下の通りグルーピングする。

| Topic | イベント |
|---|---|
| `pe.prompt` | PromptCreated / PromptVersionCreated / PromptUpdated / PromptPublished / PromptRolledBack / PromptDeprecated / PromptArchived / PromptDiscarded / PromptCompiled / PromptValidated / PromptValidationFailed / PromptOptimized / PromptRendered / CacheInvalidated |
| `pe.execution` | PromptExecuted / PromptExecutionFailed / ResponseParsed / ResponseParseFailed |
| `pe.evaluation` | PromptEvaluationCompleted |
| `pe.experiment` | ExperimentStarted / ExperimentStopped / ExperimentWinnerDeclared / ExperimentCompleted |
| `pe.governance` | PromptReviewRequested / PromptWithdrawn / PromptApproved / PromptRejected |
| `pe.plugin` | PluginRegistered / PluginActivated / PluginFailed |

`PromptReviewRequested`/`PromptWithdrawn`/`PromptApproved`/`PromptRejected`は
ADR-0016によりReviewCase自体の実装がM2へ遅延しているが、設計書§14のルーティング表は
本ADRで完全にしておく（実際に発火されるようになるのは10c以降）。

未知の`eventType`（設計書§14に無いイベント名）は`IllegalArgumentException`で
フェイルファストする。CLAUDE.mdの「設計書にないイベントを勝手に追加しない」の裏面として、
未定義のイベント種別を静かに握りつぶしたり適当なTopicへフォールバックしたりしない。

Kafkaメッセージキーは`aggregateId`を使う（同一Aggregate/ビジネスキーのイベントを
同一パーティションへ集約し、Consumer側での順序性を素朴に確保するため）。
`aggregateId`が空文字列になるケースは現行コードには存在しないが、防御的に
`eventId.toString()`へフォールバックする（`OutboxRelayer`内、`ifBlank`）。

`domain_events`をJOINして封筒を取得する`DomainEventOutboxSource`は、`claimBatch`が
`SELECT event_id, ... FROM domain_events WHERE event_id IN (:eventIds)`で封筒データを
取り直す際、SQLの`IN`句自体は行の返却順序を保証しない（CodeRabbitレビュー指摘）。
クレーム時（`ORDER BY created_at`）に確定した順序をKotlin側でMapとして保持し、
最終的な`List<OutboxEnvelope>`はそのクレーム順に組み立てる（DB側のソートに依存しない）。
購読側がイベント順序を前提にする可能性があるため、この順序保証は重要。

`KafkaEventProducer`は`eventId`をペイロードに加え、Kafkaメッセージヘッダ（`event-id`、
UTF-8文字列）にも同じ値を載せる（CodeRabbitレビュー指摘）。購読側が決定8のUNIQUE制約
チェックをペイロードの逆シリアライズ無しに行えるようにするため。

### 8. 冪等性: 購読側それぞれが`event_id UNIQUE` + `ON CONFLICT DO NOTHING`を持つ（方針のみ）

Outbox+Brokerの配信はat-least-onceになる（決定1〜3のクレームタイムアウト再クレームが
その一因でもある: クレーム後に配信が成功していたがマーク前にクラッシュした場合、
同じイベントが再送されうる）。この重複を「共有の重複排除テーブル」ではなく、
**各購読側が自分の書き込み先テーブルに`event_id UNIQUE`制約を持ち、
`INSERT ... ON CONFLICT (event_id) DO NOTHING`で書く**という個別責務のパターンに統一する。

理由: 共有の重複排除テーブルは、購読側が増えるたびに「誰がいつ確認したか」を
別途管理する必要があり、購読側ごとの保持期間・スキーマ要件（Audit/Evaluation/
Search Indexer等で必要な粒度が異なる）とも衝突する。各購読側が自分の書き込みに
対して`UNIQUE(event_id)`を持つ方式は、購読側の追加・削除がOutbox/Broker側の
実装に影響しない点でも疎結合を保てる。

本PRでは実際のKafka Subscriber（Audit/Evaluation Engine等）を実装しない
（10bスコープ、`docs/prompts/p10a.md`の分割方針）。10bでAudit/Evaluation Engineの
実装を追加する際、各自の永続化テーブルに`event_id UUID UNIQUE`列を追加し、
`ON CONFLICT (event_id) DO NOTHING`で書き込むこと。

本パターンが実際のBrokerに対して機能することを証明するため、統合テストスイート
（`tests/integration`）に**テスト専用の最小Consumer fixture**を置く。本物の
`kafka-clients`の`KafkaConsumer`でRedpandaコンテナから消費し、使い捨てのテスト用
テーブルへ上記パターンで書き込み、同一`event_id`のメッセージを2回配信しても
1行しか残らないことを検証する。このfixtureは本番コードではなく、10bが実装する
実Subscriberの前例・パターン検証のみを目的とする。

### 9. Broker: Testcontainers Redpanda（Kafka互換）

ADR-0006の「Kafka互換Broker」という表現を踏襲し、実運用のBroker製品を確定させず
「Kafkaワイヤプロトコル互換」であることのみを前提とする。統合テスト・ローカル開発の
Broker起動には`org.testcontainers:redpanda`（Redpanda、単一バイナリでKafka
プロトコルを話す）を使う。`org.apache.kafka:kafka-clients`（Producer/Consumer）は
プロトコルレベルでRedpandaと通信できるため、Broker実装をRedpandaからKafka自体や
他のKafka互換実装に切り替えてもクライアントコードの変更は不要という前提を維持する。

既存のPostgres統合テスト（`tests/integration`、Testcontainers必須・スキップ機構なし）と
同じ扱いとし、Redpandaベースの統合テストにも環境依存のスキップ機構を導入しない
（「0件ガードと同じ問題になります」という方針、`docs/prompts/p10a.md`）。

`kafka-clients`のバージョンは初版レビュー後3.8.1から3.9.2へ更新した（CodeRabbitレビュー
指摘: 3.8.1系に既知の修正が入った3.9.2以上を使う）。Redpandaとのワイヤプロトコル互換性は
バージョンに依存しないため、この更新はクライアント側のみの変更で済む。

### 10. `V12__outbox_relay_columns.sql`のインデックスは`CONCURRENTLY`を使わない（見送り、判断記録）

CodeRabbitレビューで`idx_outbox_pending`の作成を`CREATE INDEX CONCURRENTLY`にする提案が
あったが、見送った。理由は`V12__outbox_relay_columns.sql`のコメントに残す:
PostgreSQLの`CONCURRENTLY`はトランザクションブロック内で実行できない仕様だが、Flywayは
既定でマイグレーションをトランザクション内実行するため、素朴に置き換えると失敗する
（`transactional=false`相当の設定変更が別途必要になる）。本マイグレーションは
`outbox`（V1）へ列を追加した直後に同じテーブルへインデックスを張るデプロイ時の
スキーマ変更であり、対象テーブルが本番稼働で肥大化する前の段階で実行される想定のため、
通常の`CREATE INDEX`が取得する短時間のロックが実運用上の問題になるケースは想定しない。
将来、稼働中の大きな`outbox`に対してインデックスを追加し直す必要が生じた場合は、
その時点で改めて非トランザクション実行の手段を検討する。

## 影響範囲

- `prompt-engine-domain`: `domain.event`に`EventTopic`（enum）・`EventTopicResolver`
  （object）を新設。既存の`EventBusAdapter`/`DomainEvent`/`EventContext`は変更しない。
- `prompt-engine-infrastructure`:
  - `infrastructure.messaging`に`OutboxEventBusAdapter`（`EventBusAdapter`実装、
    `event_bus_outbox`へINSERT）、`OutboxSource`（インターフェース）、
    `EventBusOutboxSource`/`DomainEventOutboxSource`（実装）、`OutboxRelayer`
    （中継エンジン）、`EventProducer`（インターフェース）・`KafkaEventProducer`
    （`kafka-clients`実装）を新設。
  - `InMemoryEventBusAdapter`は変更しない（決定は`production`以外での既定のまま）。
  - マイグレーション`V11__event_bus_outbox.sql`・`V12__outbox_relay_columns.sql`を追加。
  - `kafka-clients`を`build.gradle.kts`へ追加。
- `prompt-engine-bootstrap`:
  - `AuditEventConfig.eventBusAdapter`を`auditRepositoryProduction`/`auditRepositoryDefault`と
    同じ`@Profile("production")`/`@Profile("!production")`分岐へ変更
    （`eventBusAdapterProduction`が`OutboxEventBusAdapter`、`eventBusAdapterDefault`が
    従来通り`InMemoryEventBusAdapter`）。これにより`production`プロファイルが
    `EventBusAdapter`起因では起動失敗しなくなる（Issue #35クローズの一部）。
  - `OutboxRelayConfig`（新設、`@Profile("production")`）: Kafka `Producer`・
    `EventProducer`・`OutboxSource`×2・`OutboxRelayer`×2・`ThreadPoolTaskScheduler`
    （プールサイズ2）のBean定義。
  - `OutboxRelayProperties`（`@ConfigurationProperties(prefix = "promptengine.eventbus.relay")`、
    `init`ブロックで3値すべてが正であることを検証）。
  - `OutboxRelayScheduler`（`@Profile("production")`、`@Scheduled`×2、
    専用`ThreadPoolTaskScheduler`上で並行実行）。
  - `application.yml`に`promptengine.eventbus.relay.*`・`promptengine.eventbus.kafka.*`の
    既定値を追加。
  - `EventBusAdapterProductionProfileGuardTest`（新設）: 
    (a) `production`プロファイルで`AuditEventConfig`単体を起動した場合に
    `OutboxEventBusAdapter`が選択されること、
    (b) 万一`InMemoryEventBusAdapter`を`production`で構築するBean定義が
    紛れ込んだ場合に起動失敗すること、の両方を実際にSpringコンテキストを
    起動して検証する（`ProductionProfileGuardTest`と同じ、`SpringApplicationBuilder`
    直接操作の手法）。
- `gradle/libs.versions.toml`: `kafka-clients`（3.9.2）・`testcontainers-redpanda`を追加。
- `tests/integration`: Redpanda統合テスト一式（中継・クラッシュ再クレーム・
  多重配信排除・複数インスタンス排他・**フェンシング**（claim_timeout後に別インスタンスへ
  再claimされた行を元インスタンスが上書きしないこと）、`kafka-clients`・
  `testcontainers-redpanda`を`integrationTestImplementation`へ追加。
- GitHub Issue #35（`EventBusAdapter`本実装への置換）・#11（Outbox→Broker中継）を
  本PRでクローズする。Issue #37（実DLQ）・#38・#48・#50は引き続き10b/10cで追跡する。

## 参照

- [PromptEngine_設計書.md §2.6 / §2.14 / §2.15 / §14 / §16](../PromptEngine_設計書.md)
- [ADR-0006: Persistence復元経路（`domain_events`/`outbox`、Aggregate Event Store）](0006-persistence-restore-path.md)
- [ADR-0015: Pipeline Orchestrator（決定7: `EventBusAdapter`/`InMemoryEventBusAdapter`最小実装）](0015-pipeline-orchestrator.md)
- [ADR-0016: Review Endpoints deferred to M2](0016-review-endpoints-deferred-to-m2.md)
- `docs/prompts/p10a.md`（本PRの実装プロンプト、P10分割の経緯）
- GitHub Issue #11, #35, #37, #38, #48, #50
