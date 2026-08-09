# ADR-0026: Evaluation / Audit Engine の実Subscriber、実DLQ、archiveガード（P10b）

## ステータス

Accepted

## コンテキスト

ADR-0025（P10a）はOutbox → Broker中継（`OutboxRelayer` / `KafkaEventProducer`）を実装し、
`production`プロファイルで`domain_events`/`outbox`/`event_bus_outbox`の内容が実Kafka互換
Brokerへ届くところまでを作った。一方、**購読側は1つも存在しない**。ADR-0025は決定8で
購読側の冪等性パターン（各購読側が自分の書き込み先に`event_id UNIQUE`を持ち
`INSERT ... ON CONFLICT (event_id) DO NOTHING`で書く）を方針として定め、実際のSubscriber
実装・実DLQ・Micrometer計測を10b/10cへ明示的に先送りした。

その結果、設計書§12のテーブルのうち`execution_logs`は**書き込み経路がゼロ**のまま残り、
それに依存する2つの機能が塞がっている。

- `evaluation_records`（設計書§2.12 Evaluation仕様）: 評価を実行・永続化する主体が無い
- `ArchiveHandler`のガード（設計書§2.5「参照クライアントゼロ確認 or 強制フラグ」）:
  参照実績を判定する材料が無く、`force=true`のみ受け付ける暫定実装（Issue #48）

本ADRはP10b（`docs/prompts/p10b.md`、Issue #37・#48の回収）の対象範囲として、以下を確定する。

1. `execution_logs`を誰が書くか、購読の仕組みをどう作るか
2. 実DLQの形態（テーブルかBrokerトピックか、再処理手段、退避の検知）
3. Evaluation Engineの構造（M1の評価器、Quality系Pluginの拡張点）
4. Auditの全イベント購読とSecretマスクの担保
5. archiveガードの`execution_logs`ベース化と、その原理的な限界の扱い
6. Cache Invalidator / Search Indexer

本ADRのスコープ外（10cへ先送り）: Micrometer/OTelによる購読側の計測、DLQの自動再処理、
Experiment（`variant_id`）との連携、Quality系評価器の実装そのもの。

## 決定

### 1. `execution_logs`はPipelineのStageではなく専用Subscriberが書く。購読は実Kafka Consumer

設計書§14は`PromptExecuted`の購読先を「Evaluation Engine, Monitoring, Audit」と定めており、
`execution_logs`への記録はMonitoring側の非同期fan-outに当たる。Pipelineのステージは
`ExecutionStage`が確立した「既存Engineへの薄い委譲」に留める方針であり、既に`AuditRecord`の
追記だけを担っている`AuditStage`（ステージ12、ADR-0015決定7）へ`execution_logs`の書き込みまで
足すと、1つのステージが2つの独立した記録先を持つことになりこの形が崩れる。

このため`ExecutionLogSubscriber`（`prompt-engine-infrastructure`）を新設し、`AuditStage`には
一切手を入れない。`EvaluationEngine`も`ExecutionLogSubscriber`から独立させ、片方の失敗が
もう片方の記録を巻き添えにしないようにする（両者は同じ`PromptExecuted`を別々のconsumer group
で受け取る）。

購読の仕組みは**実Kafka互換Consumer**（`kafka-clients`の`KafkaConsumer`、`KafkaEventProducer`と
同じ方針。Spring Kafkaは本プロジェクトの依存に無い）とし、プロセス内の同期ディスパッチには
しない。`KafkaSubscriberRunner`が1サイクル分の`pollOnce()`を公開し、`prompt-engine-bootstrap`の
`@Scheduled`ジョブ（`SubscriberScheduler`、専用`ThreadPoolTaskScheduler`プールサイズ5）が
周期起動する。`OutboxRelayConfig`/`OutboxRelayScheduler`の形をそのまま踏襲し、
`@Profile("production")`限定とする（非productionは`InMemoryEventBusAdapter`のままで購読対象が
存在しない、ADR-0025決定5と同じ扱い）。

購読側は5つ。それぞれ**自分専用のconsumer group**（`EventSubscriber.name`）を持つ。

| 購読側 | Topic | 書き込み先 |
|---|---|---|
| `AuditEngine` | 6トピック全て | `audit_logs` |
| `ExecutionLogSubscriber` | `pe.execution` | `execution_logs` |
| `EvaluationSubscriber` | `pe.execution` | `evaluation_records` + `PromptEvaluationCompleted`発行 |
| `CacheInvalidationSubscriber` | `pe.prompt` | キャッシュ無効化ポート |
| `SearchIndexSubscriber` | `pe.prompt` | 検索インデックスポート |

#### 1a. Brokerメッセージ本文をpayload単体から封筒全体のJSONへ変更する

P10aの`OutboxRelayer`は本文として`payload`のJSONだけを送っていた。当時の購読側は
テスト専用fixtureのみで、`event_id`さえヘッダから読めれば冪等性パターンを実証できたため
十分だった。

P10bの実購読側は`payload`だけでは成立しない。`AuditEngine`は`audit_logs`へ
`aggregate_type`/`action`（＝`eventType`）/`actor`/`trace_id`/`occurred_at`を書く必要があり、
`ExecutionLogSubscriber`も`trace_id`を要る。これらは`DomainEvent`の封筒フィールドであって
`payload`には含まれない。

このため本文を封筒全体のJSON（`EventEnvelopeCodec`）へ変更する。`payload`は入れ子の
JSONオブジェクトとして埋め込む（文字列としてエスケープしない）。実購読側がまだ存在しない
段階での変更であり、互換性を気にする既存コンシューマは無い。`event-id`ヘッダ
（ADR-0025決定7）は引き続き載せる。

#### 1b. `PromptExecuted`のpayloadを拡張する

P10a時点のpayload（`promptKey`/`inputTokens`/`outputTokens`/`retryCount`）では
`execution_logs`（`version_id`・`latency_ms`・`status`・`cost`が必須、設計書§12）も
EvaluationのLatency/Cost指標（設計書§2.12）も算出できない。`semVer`/`latencyMs`/
`costPerToken`/`status`を追加する。

- `semVer`: 購読側が`prompt_key` + `version`から`prompt_versions.version_id`
  （永続化サロゲートUUID）を解決するために必要。イベントは業務キーしか運ばない
  （ADR-0025決定1）ため、サロゲートキーへの変換は書き込み時にinfrastructure層で行う
  （`domain_events`/`audit_logs`の`aggregate_id`と同じ方針、V1マイグレーションのコメント参照）。
- `latencyMs`: `PipelineContext.stageDurationsMs["Execution"]`（設計書§2.12「Latency |
  Execution Stage実測」）。`PipelineOrchestrator`以外の経路で未計測の場合は各試行の
  `RawResponse.latency`の合算へフォールバックする。
- `costPerToken`: 実行時点の`ModelProfile.costPerToken`。購読側が後からModelProfileを
  引き直すと、単価改定後に過去の実行を再評価した際に当時と違うコストが出るため、
  実行時点の単価をイベント自身に載せる。
- `status`: `execution_logs.status`（NOT NULL）用。`EvaluationStage`はStage 9が成功して
  Stage 11まで到達した場合にしか動かないため、M1では常に`SUCCESS`。

### 2. 実DLQは専用テーブル`dead_letter_queue`。再処理は手動、検知はログ＋件数

Brokerの専用DLQトピックではなくPostgresの専用テーブルとする。理由:

- 運用者がSQLで中身を確認・抽出でき、再処理の判断材料（`retry_count`・`first_failed_at`・
  `failure_reason`）を同じ場所に置ける
- 購読側ごとの再処理状態（`status`）を持てる。Brokerトピックは「読んだ／読んでいない」しか
  表現できず、「調査中」「再処理済み」といった運用上の状態を別途管理する必要が生じる
- 本プロジェクトは既にOutbox（`event_bus_outbox`）でRDBをメッセージ制御の一次記録として
  使っており、DLQだけBroker側に置くと運用の参照先が二分される

購読側を`subscriber_name`で識別する汎用形にし、P10bで実際に配線するのはAudit書き込み失敗
経路（Pipeline Stage 12とBroker購読側の両方）のみとする。

**再処理は手動**（運用者が起動するコマンド／管理経路）を前提とし、自動リトライポーラは
持たない（M1スコープ外）。恒久的な失敗（例: 参照先Promptが既に削除済み）を自動再試行し
続けても解消しないため。

**検知**は2本立てとする。新しいメトリクスバックエンド（Micrometer等）は本ADRでは導入しない
（10cスコープ）。

1. 退避1件ごとの構造化ERRORログ（`dead_letter_enqueued`）
2. `DeadLetterQueueRepository.pendingCount()`（`status='PENDING'`の件数）。監視側が
   ポーリングしてゲージ化できる

**冪等キーは`(event_id, subscriber_name)`**。at-least-once配信で同じイベントが再配信され
再び同じ購読側で失敗しても、DLQ行が際限なく増えず`retry_count`/`last_failed_at`の更新に
集約される。`event_id`がNULLの行（Pipeline Stage 12の`AuditRecord`退避経路。この経路は
キーにできるイベントを持たない）は、PostgreSQLがUNIQUE制約上NULLを互いに異なる値として
扱うため失敗の都度1行ずつ積まれる（この経路では失敗発生回数そのものが記録として意味を持つ）。

**失敗したメッセージのオフセットはコミットする。** 退避済みのメッセージを再消費し続けると、
その1件が後続の全メッセージの処理を永久に止めてしまう（poison pill）。DLQへ退避した時点で
「このメッセージの処理は打ち切った」ことが記録として残るため、Broker側で保持し続ける必要は無い。

`AuditFailureHandler`の実装は`Slf4jAuditFailureHandler`（ログのみ、M1暫定）から
`DeadLetterQueueAuditFailureHandler`へ差し替える。`Slf4jAuditFailureHandler`はdelegateとして
残す（DLQ側のログは`event_id`/`subscriber_name`中心でPipeline文脈（traceId/promptKey/mode）を
持たないため、両方あった方が運用時の追跡が容易）。**これによりIssue #37をクローズする。**

### 3. `EvaluationRule`はdomainの拡張点、`EvaluationEngine`はdomainポート・実装はcore

設計書§2.12の指標のうち、M1で実装するのは実行系の3つ（Latency / Token Usage / Cost）。
Quality系（Prompt Quality / Response Quality / Accuracy / Consistency / Determinism）は
Pluginとして後から足せる構造にする必要がある（`docs/prompts/p10b.md`）。

このため`EvaluationRule`（`metricType` / `method` / `evaluate`）をdomainの唯一の拡張点として
定義し、`EvaluationEngineImpl`（`prompt-engine-core`）は登録された`EvaluationRule`のリストを
順に回すだけで個々の指標を知らない構造にする。Quality系の追加は`EvaluationRule`実装を
リストへ足すだけで済み、Engine側の変更を要さない。

`EvaluationEngine`自体は**domainのインターフェース**とし、実装をcoreに置く。購読・永続化・
イベント発行を担う`EvaluationSubscriber`は`prompt-engine-infrastructure`にあるため、
Engineを具象型で参照すると infrastructure → core というモジュール依存が新たに生まれる。
domainのインターフェース越しにすることで、CLAUDE.mdの「core / infrastructure はdomainが
定義したInterfaceを実装する側」という関係を保つ。

補助的な決定:

- `evaluate()`が`null`を返した指標は行を書かない。「算出できない」と「スコアが0」を
  スコア0で潰さないため
- 1つの評価器の例外が他の評価器の結果を巻き添えにしない。握り潰しも避けるため
  `EvaluationRuleFailureHandler`（domain）へ委譲する。coreはSLF4Jへ依存できないため、
  `AuditFailureHandler`と同形の逃がし口とし、SLF4J実装はinfrastructureに置く
- `metricType`の重複は`evaluation_records`の冪等キーと衝突するため構築時にfail-fastさせる
- Costは`(inputTokens + outputTokens) × costPerToken`。`ModelProfile.costPerToken`は入出力を
  区別しない単一のブレンド単価であり実課金の入出力別レートを表現できないが、設計書§2.12の
  記述（「usage × Model Profile単価」）自体が単価を単数で書いているため矛盾しない
- `metric_type`は設計書§2.12の表記に対し空白なしの識別子を使う（"Token Usage" → `TokenUsage`）
- 保存が0件（＝全て重複＝再配信）の場合は`PromptEvaluationCompleted`を再発行しない。
  再配信のたびに下流へ完了イベントが増殖するのを避けるため

### 4. Auditは全6トピックを購読。Secretマスクは2層で担保する

`AuditEngine`は設計書§14の6トピック全てを購読し、届いた全イベントを`AuditRepository.record()`
（ADR-0017の一般形）で`audit_logs`へ1行ずつ追記する。Pipeline専用の狭い`AuditRecord`/`append()`
（ADR-0015決定7）は流用しない。

設計書§12は`audit_logs.payload`を「Secretマスク済」と定めている。`AuditEngine`は
**具象クラスがまだ存在しないイベント種別も含めて無差別に保存する**立場であり、
「payloadに秘密が入らない」ことを型だけで保証しきれない。このため2層で守る。

- **第1層（型ベース、`SensitiveValueMaskingModule`）**: `SensitiveValue`を常に`"***"`として
  シリアライズするJacksonモジュールをアプリケーション全体の`ObjectMapper`へ登録する。
  Outbox（`event_bus_outbox`/`domain_events`）へ書かれる**入口**でマスクされるため、
  下流の購読側は既にマスク済みのJSONを受け取る。経路ごとに専用の`ObjectMapper`を用意して
  掛け忘れる余地を残すより、単一の`ObjectMapper`に対して一度宣言する方が、新しい
  シリアライズ経路が追加されても自動的に保護される
- **第2層（名前ベース、`SecretMaskingJsonSanitizer`）**: 保存直前にフィールド名で
  redactする。型を経由せず生の`String`として秘密が混ざるイベントが将来追加される可能性への
  多層防御。DLQへの退避内容にも同じサニタイザを通す（DLQは運用者が中身を目視する前提の
  テーブルであり、監査ログと同じ厳しさを要求する）

第2層の照合は**後方一致**とする。部分一致にすると`inputTokens`/`outputTokens`/`totalTokens`/
`tokenizerId`（いずれも`token`を含む）までマスクされ、`PromptExecuted`のpayloadの中心的な
データが失われて監査記録が実質的に無意味になる（実装時にテストで検出した誤検知）。
名前ベースの照合は原理的に不完全（列挙に無い名前は素通りする）だが、第1層と組み合わせる
ことで「型を通った秘密」と「典型的な名前の秘密」の両方を塞ぐ。

### 5. archiveガードはカットオーバー時刻＋無活動期間で判定する

`execution_logs`への書き込みはP10b以降にしか存在しない。したがって「`execution_logs`に行が
無い」は2つの全く異なる状況を同じ形で表す。

1. 本当に一度も実行されていない（＝参照ゼロ。archiveしてよい）
2. P10b以前から存在するPromptで、実行されていても記録が残っていない（＝判断不能）

ガードを素朴に実装すると、古いPromptが全部archive可能になってしまう
（`docs/prompts/p10b.md`が明示的に論点として挙げている箇所）。

このため**カットオーバー時刻**（`promptengine.archive.execution-logs-cutover-at`、既定は
P10bのシップ日`2026-08-09T00:00:00Z`）を設定し、`prompt_versions.created_at`と比較する。

| 条件 | 判定 | `force=false`での挙動 |
|---|---|---|
| Versionが存在しない | `VersionNotFound` | `PromptVersionNotFoundException` |
| `created_at` < カットオーバー | `PreCutover` | 拒否（従来通りforce必須） |
| 判定窓の中に実行記録がある | `RecentlyExecuted` | 拒否 |
| 判定窓の中に実行記録が無い | `Inactive` | **許可** |

`force=true`は判定によらず常に通る（ガード自体を呼ばない）。判定窓は
`promptengine.archive.inactivity-threshold-days`（既定90日。設計書に既定値の記載が無いため
選定した値）。

**既知の限界（意図的に受け入れたトレードオフ、不具合ではない）**: カットオーバー以前に
作られたVersionは`force=false`のarchiveを**常に拒否**し（`ArchiveRequiresForceException`）、
**恒久的に`force=true`専用のまま**残る。

「`execution_logs`に行が無い」と「一度も実行されていない」は**区別できていない**。
cutoff前に作成されたVersionについて、現在の実装は次の2つをどちらも`PreCutover`として
同じ扱いにする。

1. cutoff前に作成され、その後一度も実行されていない古いPrompt（＝本当に参照ゼロ。
   本来はforce無しでarchiveできてよいのに拒否される）
2. cutoff前に作成され、cutoff前は活発に実行されていたが記録が残っていないだけのPrompt
   （＝実際には参照されている可能性がある）

**拒否側へfail closedさせた根拠は、取り違えた場合の損害が非対称であること。**
1を誤って拒否しても運用者が`force=true`を付け直すだけで回復できる（可逆・低コスト）。
一方2を誤って許可すると、現に参照されているPromptを警告なくArchivedへ落とし、
その参照元（AACP等の外部クライアント）が実行時に失敗する（不可逆・影響が外部へ波及）。
ガードの目的は「参照されているものを誤って落とさない」ことであり、判断不能を許可側へ倒すと
その目的そのものを果たさなくなる。

将来これを解消するには、別の参照追跡手段（AACP側のクライアント登録等）を導入するか、
運用でカットオーバーを引き直す（cutoff以降の実行実績が十分に蓄積した時点で、cutoffを
過去方向へ動かさずに前進させる）必要がある。

判定は`ArchiveEligibilityRepository`という狭いポートに閉じ込め、`prompt_versions.created_at`と
`execution_logs`の突き合わせをinfrastructure層のSQL側の責務とする。`PromptVersion` Aggregateは
`created_at`を公開していないが、判定のためだけにドメインモデルへ永続化メタデータを
持ち込むことは避ける。**これによりIssue #48をクローズする。**

### 6. Cache Invalidator / Search Indexer は最小のポート＋簡易実装

既存のキャッシュ無効化ポート・検索インデックス更新ポートはリポジトリ内に存在しなかった
（読み取り側の`PromptSearchRepository`はRDBのLIKE検索であり別の関心事）ため、最小のポートを
domainに新設する。

- `PromptCacheInvalidator.invalidateByPrompt(key)`: 実装はプロセス内記録＋構造化ログ。
  PE内にRender結果／Composition結果の永続キャッシュ（設計書§2.14のRedis）がまだ無いため
- `PromptSearchIndexer.index(key)` / `remove(key)`: `docs/prompts/p10b.md`が「Search Indexerは
  M1では簡易実装でよい」と明示しているため、外部検索基盤へは接続しない

**購読とポート呼び出しの配線自体は本物**であり、実キャッシュ／実検索基盤を入れる際は
ポートの実装を差し替えるだけで済む。

`CacheInvalidationSubscriber`は`PromptPublished`に加え`PromptRolledBack`/`PromptArchived`/
`PromptDiscarded`も対象とする（いずれも配信されるPromptの内容が実質的に切り替わる、
設計書§14の`pe.prompt`イベント）。`pe.prompt`には`domain_events`由来のイベントも流れ、
そちらの`aggregateId`は`prompts.prompt_id`（UUID文字列）で`PromptKey`として解釈できない
（ADR-0025決定7）。この場合は無効化対象を特定できないため何もしない（例外にしてDLQを汚さない）。

### 7. `evaluation_records`の冪等キーは`(event_id, metric_type)`の複合とする

ADR-0025決定8は「各購読側が自分の書き込み先テーブルに`event_id UNIQUE`制約を持つ」と定めたが、
`evaluation_records`は1つの`PromptExecuted`から複数の評価器（M1では3つ）が行を書くため、
`event_id`単独のUNIQUEでは2つ目以降が`ON CONFLICT DO NOTHING`で恒久的に捨てられてしまう。

決定8の趣旨は「自分の書き込み先テーブルの粒度に合った冪等キーを持つ」ことであり、
このテーブルの粒度は`(イベント, 指標)`である。したがって`UNIQUE (event_id, metric_type)`とする。
`execution_logs`（1イベント＝1行）と`audit_logs`（1イベント＝1行）は`event_id`単独でよい。

`audit_logs.event_id`はNULL許容とする。既存のCRUD/lifecycle経路（`AuditRepository.record()`、
ADR-0017）とPipeline Stage 12（`append()`、ADR-0015決定7）はキーにできるイベントを持たないため。

### 8. `prompt-engine-infrastructure`は統合テストのカバレッジを集約して下限ゲートを掛ける

`docs/prompts/p10b.md`は「カバレッジはbuildSrcの下限を下回らないこと」を要求し、P10bでは
`prompt-engine-infrastructure`に相当量の新規コードが入る。

このモジュールの既定の`jacocoTestReport`は自プロジェクトの`test`（単体テスト）のexecしか
読まない。一方モジュールの主要部分であるJDBC RepositoryはCLAUDE.mdのテスト規約
（「Infrastructureは Testcontainers を使った統合テスト」）に従い`tests/integration`で
検証しており、そちらは別Gradleプロジェクトのため実行データがこのレポートへ入らない。
そのため単体のみの実測は 行24.01% / 分岐35.46% と、実態から大きく乖離した値になっていた。

**この層はテストが薄いのではなく、測り方が実態と合っていなかった。** 統合テストの
実行時カバレッジを合算すると 行**95.88%** / 分岐**84.87%** である。P10bで実際に見つかった
不具合（Secretマスキングのフィールド名誤検知）がこの層に集中したことも踏まえ、
ゲート対象外のままにはしない。

実装:

- `tests/integration`に`jacoco`プラグインを適用し、`integrationTest`の実行時カバレッジを
  `build/jacoco/integrationTest.exec`へ固定パスで出力する
- buildSrcの`promptengine.kotlin-conventions`に、オプトイン式の集約タスク対
  `jacocoAggregatedReport` / `jacocoAggregatedCoverageVerification`を追加する
- `prompt-engine-infrastructure`が下限 行90% / 分岐80%（集約後の実測を下回る切りの良い値。
  他モジュールと同じ「劣化のみ検知」方針）を設定する
- CIの`test`ジョブが`integrationTest`のあとに`jacocoAggregatedCoverageVerification`を呼ぶ

**既定の`jacocoTestCoverageVerification`（`extra["jacocoMinLineCoverage"]`）へ統合テストの
execを合流させるのではなく、専用タスク・専用キーにした理由**: 既定の検証タスクは`check`
（延いては`build`）へ配線済みであり、そこへ統合テストのexecを合流させると
**`./gradlew build`だけでDocker（Testcontainers）が必要になる**。これは
`tests/integration/build.gradle.kts`冒頭が明示的に避けている挙動であり、CLAUDE.mdの
`test`と`integrationTest`の使い分けにも反する。集約検証は`check`に配線せず、
CIから明示的に呼ぶ形にすることで、ゲートを効かせつつローカルの`build`をDocker非依存に保つ。

（プリコンパイル済みスクリプトプラグインの本体はモジュールの`build.gradle.kts`が`extra`を
設定する**前**に実行されるため、設定の読み取りとタスク登録は`afterEvaluate`で行う必要がある。
既存の`minLineCoverage`が動いていたのは、`tasks.named{}`の設定アクションが遅延評価される
副次的な理由による。）

### 9. P10bで行った判断の棚卸し（設計書・ADRへの反映要否）

P10bの実装過程で、設計書に明示が無い判断を複数行った。レビュー可能性のため、
それぞれについて (a) 設計書の記述と食い違うか (b) 観測可能な意味論（同じ入力に対する
出力・APIの挙動・永続化される値）を確定させるか を判定し、いずれかに該当するものは
設計書本体へも反映した（CLAUDE.md「設計上の決定をコミットメッセージやコードコメントにのみ
記録しない」）。

| # | 判断 | a | b | 反映先 |
|---|---|---|---|---|
| 1 | Brokerメッセージ本文を封筒8フィールド全体にする | ○ | ○ | ADR-0025訂正E1 / 本ADR決定1a / §14 |
| 2 | `semVer`をSemVerオブジェクトとして運ぶ（文字列にしない） | − | ○ | 本ADR決定1b / §14 |
| 3 | `costPerToken`を実行時点の値としてイベントへ載せる | − | ○ | 本ADR決定1b / §2.12 |
| 4 | `latencyMs`未計測時は各試行のlatency合算へフォールバック | − | ○ | 本ADR決定1b / §2.12 |
| 5 | `status`はM1では常に`SUCCESS` | − | ○ | 本ADR決定1b / §2.12 |
| 6 | `evaluation_records`の冪等キーを`(event_id, metric_type)`にする | − | ○ | 本ADR決定7 / §12 |
| 7 | `execution_logs.event_id`はNOT NULL、`audit_logs.event_id`はNULL許容 | − | ○ | 本ADR決定7 / §12 |
| 8 | DLQの冪等キーを`(event_id, subscriber_name)`にする（NULLは都度1行） | − | ○ | 本ADR決定2 / §12 |
| 9 | `variant_id`はM1では常にNULL | − | ○ | 本ADR決定3 / §2.12 |
| 10 | 処理に失敗したメッセージもオフセットをコミットする | − | ○ | 本ADR決定2 / §14 |
| 11 | `failure_reason`は例外クラス名のみ（メッセージ本文を入れない） | − | ○ | 本ADR決定2 / §12 |
| 12 | 重複再配信時は`PromptEvaluationCompleted`を再発行しない | − | ○ | 本ADR決定3 / §14 |
| 13 | `metric_type`は空白なし識別子（"Token Usage"→`TokenUsage`） | ○ | ○ | 本ADR決定3 / §2.12 |
| 14 | `caller_system`はイベントの`actor`で代替。Costは単一のブレンド単価 | − | ○ | 本ADR決定1b・3 / §2.12 |
| 15 | キャッシュ無効化は`PromptRolledBack`/`Archived`/`Discarded`でも発火 | − | ○ | 本ADR決定6 / §14 |
| 16 | archiveガードのcutoff仕様（cutoff前はforce必須） | − | ○ | 本ADR決定5 / §2.5 |
| 17 | Secretマスクを型ベース＋名前ベースの2層にし、型ベースは全体の`ObjectMapper`へ適用 | − | ○ | 本ADR決定4 / §14 |
| 18 | `EvaluationEngine`をdomainポートにし実装をcoreへ置く | − | − | 本ADR決定3のみ（内部構成） |
| 19 | `EvaluationRuleFailureHandler`を新設（coreがSLF4Jへ依存できないため） | − | − | 本ADR決定3のみ（内部構成） |
| 20 | `ArchiveGuardSettings`・`SubscriberDeadLetterRecorder`の切り出し、bootstrapのConfig分割 | − | − | 反映不要（内部構成・detekt閾値対応） |
| 21 | `EvaluationConfig`は`production`限定にしない（ArchiveHandlerが全プロファイルで必要） | − | − | 反映不要（DI配線の詳細） |
| 22 | 統合テストの決定性確保（配信回数カウンタ＋DB状態ポーリング） | − | − | 反映不要（テスト実装の詳細） |

18〜22は純粋な内部実装の選択であり、外部から観測できる意味論を変えないため設計書本体への
反映は行わない。

## 影響範囲

- `prompt-engine-domain`:
  - `domain.event`に`EventEnvelope`・`EventSubscriber`を新設
  - `domain.evaluation`パッケージを新設（`EvaluationRule`・`EvaluationEngine`・
    `EvaluationRecord`・`EvaluationRepository`・`EvaluationRuleFailureHandler`・
    `PromptExecutionSummary`・`ExecutionStatus`・`ExecutionLogEntry`・
    `ExecutionLogRepository`・`PromptEvaluationCompletedEvent`）
  - `domain.dlq`（`DeadLetterEntry`・`DeadLetterQueueRepository`）、`domain.cache`
    （`PromptCacheInvalidator`）、`domain.search`（`PromptSearchIndexer`）を新設
  - `domain.prompt`に`ArchiveEligibility`・`ArchiveEligibilityRepository`・
    `ArchiveGuardSettings`を追加。`ArchiveRequiresForceException`のメッセージを更新
  - `AuditLogEntry`にnullable `eventId`を追加。`AuditFailureHandler`のKDocからIssue #37の
    先送り記述を削除
  - `PromptExecutedEvent.Payload`を拡張（決定1b）
- `prompt-engine-core`: `engine.evaluation`に`EvaluationEngineImpl`と3評価器を新設
- `prompt-engine-application`: `EvaluationStage`が拡張payloadを構築。`ArchiveHandler`が
  `ArchiveEligibilityRepository`でガードする
- `prompt-engine-infrastructure`:
  - `infrastructure.subscriber`（`KafkaSubscriberRunner`・`SubscriberDeadLetterRecorder`・
    `AuditEngine`・`ExecutionLogSubscriber`・`EvaluationSubscriber`・
    `CacheInvalidationSubscriber`・`SearchIndexSubscriber`・`PromptExecutedPayloadCodec`）を新設
  - `infrastructure.masking`（`SensitiveValueMaskingModule`・`SecretMaskingJsonSanitizer`）を新設
  - `infrastructure.messaging`に`EventEnvelopeCodec`を追加。`OutboxRelayer`が本文を封筒JSONへ
  - `infrastructure.persistence`に`JdbcExecutionLogRepository`・`JdbcEvaluationRepository`・
    `JdbcDeadLetterQueueRepository`・`JdbcArchiveEligibilityRepository`・
    `PromptVersionIdResolver`を新設。`JdbcAuditRepository`が`event_id`を扱う
  - `infrastructure.audit`に`DeadLetterQueueAuditFailureHandler`、`infrastructure.evaluation`に
    `Slf4jEvaluationRuleFailureHandler`、`infrastructure.cache`/`infrastructure.search`に
    M1実装を新設
  - マイグレーション`V13__subscriber_idempotency_and_dlq.sql`を追加
- `prompt-engine-bootstrap`: `EvaluationConfig`・`SubscriberConfig`・`EventSubscriberConfig`・
  `MessagingSupportConfig`・`SecretMaskingConfig`・`SubscriberScheduler`・
  `SubscriberProperties`・`ArchiveGuardProperties`を新設。`AuditEventConfig`が
  DLQ実装を配線。`CommandHandlersConfig`が`ArchiveHandler`へガードを注入。
  `application.yml`に`promptengine.eventbus.subscriber.*`・`promptengine.archive.*`を追加
- `tests/integration`: `EventSubscriberIntegrationTest`（Redpanda+Postgres）・
  `ArchiveGuardIntegrationTest`・`JdbcEvaluationRepositoryIntegrationTest`を追加。
  `JdbcMetricsRepositoryIntegrationTest`の直接INSERTへ`event_id`を追加
- GitHub Issue #37（実DLQ）・#48（archiveガード）を本PRでクローズする

## 参照

- [PromptEngine_設計書.md §2.5 / §2.6 / §2.12 / §2.14 / §2.15 / §12 / §14](../PromptEngine_設計書.md)
- [ADR-0015: Pipeline Orchestrator（決定7: AuditStage / EventBusAdapter）](0015-pipeline-orchestrator.md)
- [ADR-0017: REST API read model ports（`AuditRepository.record`/`AuditLogEntry`）](0017-rest-api-read-model-ports.md)
- [ADR-0025: Event Bus 実装（Outbox → Broker中継）](0025-event-bus-outbox-relay.md)
- `docs/prompts/p10b.md`（本PRの実装プロンプト）
- GitHub Issue #37, #48
