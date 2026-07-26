# ADR-0006: 永続化層からの復元は Memento + @PersistenceApi opt-in に限定する

## ステータス

Accepted

## コンテキスト

P1 では、外部コードが `Prompt.create` / `.newVersion` に任意状態の `PromptVersion` を
渡して不変条件（新規Versionは常にDraft）を迂回できないよう、`state` を持たない
`NewPromptVersion` を導入した（P1メモ参照）。

しかし `PromptVersion` 本体・`Prompt` 本体のプライマリコンストラクタは現状
どちらも完全に `public` である（`state: LifecycleState` を含む全プロパティが
コンストラクタ引数として外部から指定可能）。そのため `NewPromptVersion` が塞いだのは
`create`/`newVersion` の入口のみであり、`PromptVersion(semVer, content, ..., state =
LifecycleState.Published)` を直接呼んで `Prompt(key, listOf(...))` に詰める経路は、
`prompt-engine-infrastructure` だけでなく `prompt-engine-application` からも現時点で
素通りできる。P2 で永続化層がPublished/Deprecated/ArchivedのVersionをDBから復元する
必要が生じたことで、この穴を放置できなくなった。

Kotlinの `internal` 可視性はGradleモジュール単位（コンパイル単位）で区切られる。
`domain` モジュールの `internal` は `domain` 自身からしか見えず、`infrastructure` は
`application` と同様に `domain` の外側にある別モジュールである。そのため
「`domain` の `internal` を単純に使って `infrastructure` にだけ復元を許可する」ことは
不可能であり、`internal` 化は「誰にも復元できない」か「誰でも構築できる（現状）」の
どちらかにしかならない。

比較した2案:

- 案A（採用）: `PromptVersion`/`Prompt` のプライマリコンストラクタを `internal` にして
  どのモジュールからも直接構築できなくした上で、行（DBスキーマ）形状に対応する
  `PromptVersionMemento`/`PromptMemento` VOを追加し、`domain` 内部からのみ呼べる
  `internal constructor` を使う `Prompt.restore(memento)` ファクトリを `domain` に置く。
  このファクトリ自体は `public` だが `@RequiresOptIn` マーカー `@PersistenceApi` を付与し、
  呼び出し側に `@OptIn(PersistenceApi::class)` の明示を要求する。
  `@RequiresOptIn` はモジュール境界と無関係にコンパイル時チェックされるため、
  `internal` ではできない「infrastructureだけに許可する」が実現できる。
  ArchUnitで `promptengine.infrastructure.persistence..` 以外のパッケージが
  `@PersistenceApi` に依存していないことを機械的に検証する。
- 案B: Event Sourcingのリプレイのみに一本化し、`domain_events` を`submitForReview`/
  `approve`/`publish` 等の**既存の遷移メソッドをそのまま**再生することで状態を再構築する。
  状態を直接構築するコードパス自体をなくせる点で理論上はより「純粋」だが、
  各遷移メソッドは `validationPassed`・`approvalCount`・`allDependenciesPublished` 等の
  外部コンテキストをガード条件として要求するため、リプレイ時にそれらをどこから
  得るか（イベントpayloadに全部持たせるか、ガードを無視する「信頼済みリプレイ」
  モードを別途作るか）という、結局は案Aと同種の「公開だが特別なコードパス」問題を
  イベント適用側に移し替えるだけになる。さらに設計書§2.14は
  「Event Store（追記専用）+ Snapshot。RDBに現在状態も投影（運用容易性のため）」と
  明記しており、`findByKey` の主経路はRDB投影からの読み出しであってイベント全リプレイ
  ではない。案Bは§2.14の想定するアーキテクチャと整合しない。

## 決定

案Aを採用する。

- `PromptVersion` のプライマリコンストラクタを `internal constructor` に変更する。
  `domain` モジュール内（`Prompt.create`/`.newVersion`/`Prompt.restore`/テスト）からのみ
  呼び出し可能になる。
- `Prompt` のプライマリコンストラクタも同様に `internal constructor` にする。
- `promptengine.domain.prompt` に `@RequiresOptIn(level = RequiresOptIn.Level.ERROR)`
  マーカーアノテーション `@PersistenceApi` を追加する。
- `PromptVersionMemento`（`semVer, content, variables, contextRequirement, state`）と
  `PromptMemento`（`key, versions: List<PromptVersionMemento>`）をDBの行形状に対応する
  VOとして追加する。命名は §6.3 で言及される「Event Storeスナップショット」との混同を
  避けるため意図的に "Snapshot" を避け "Memento"（GoF Mementoパターン）とする。
- `Prompt.Companion` に `@PersistenceApi fun restore(memento: PromptMemento): Prompt` を
  追加する。この関数は不変条件（Publishedは同時に1Versionまで、`Prompt` の `init` が
  検証）はそのまま強制するが、遷移の正当性（どの順序でその状態に至ったか）は
  検証しない ── DBの行自体が過去の正当な遷移列の結果であることを信頼する。
- `prompt-engine-infrastructure` の `PromptRepository` 実装（`promptengine.infrastructure
  .persistence` パッケージ）のみが `@OptIn(PersistenceApi::class)` を用いて
  `Prompt.restore` を呼んでよい。ArchUnitで
  `promptengine.infrastructure.persistence..` 以外のクラスが `PersistenceApi` を
  参照していないことを検証する（`@RequiresOptIn` 自体もコンパイル時に強制するため、
  ArchUnitルールは二重の安全網）。
- Event Sourcingの復元経路（案B）は採用しないが、Event Store自体（追記専用ログ、
  監査・再構築用）は §6.3 の指示どおり実装する。`findByKey` はRDB投影
  （`prompts`/`prompt_versions`テーブル）から `PromptMemento` を組み立てて
  `Prompt.restore` に渡す。

### 補足: `copy()` の可視性

Kotlin 2.0.20時点（本リポジトリは2.0.21採用）では、`data class` のコンストラクタを
`internal`/`private` にしても、コンパイラが自動生成する `copy()` はデフォルトで
**public のまま**という既知の非対称仕様がある（`copy(state = LifecycleState
.Published)` のように呼べば、internal化したコンストラクタを素通りしてPublished状態
の `PromptVersion` を作れてしまう）。この挙動をopt-inで閉じる
`@ConsistentCopyVisibility` アノテーション（`kotlin.ConsistentCopyVisibility`、
stdlib）が2.0.20で追加されており、これを `PromptVersion`/`Prompt` の両方に付与し、
`copy()` の可視性をコンストラクタと一致させる（`internal` になる）。
将来のKotlinバージョンでこれがデフォルト挙動になった際は本アノテーションは
冗長になるが、削除する積極的理由がない限りそのまま残してよい。

### §12 ER図の拡張: `prompt_snapshots` テーブル追加

§6.3 手順4「sequence が N 件を超えたらスナップショット保存」に対応するテーブルが
§12 のER図に存在しないため、新規に追加する（これはDomain層の変更ではなく、
Infrastructure層のEvent Store最適化用テーブルであり、上記の `PromptMemento`
とは無関係。両者を "Snapshot" という同一語で呼ぶと混同するため、Event Store側は
そのまま「スナップショット」、Domain側は「Memento」と呼び分ける）。

```
entity prompt_snapshots {
  * snapshot_id : UUID <<PK>>
  --
  * aggregate_id : UUID <<FK -> prompts.prompt_id>>
  * sequence : BIGINT   ' 保存時点の domain_events.sequence 最大値
  * state : JSONB       ' 直列化された集約状態（復元用）
  * created_at
  <<UQ aggregate_id+sequence>>
}
prompt_snapshots }o--|| prompts : aggregate_id
```

`prompt_snapshots.aggregate_id` はPromptに固有（`PromptMemento`のスナップショット）
なので実FKを張るが、`domain_events.aggregate_id` は次項の通りFKを張らない
（`prompts}o--||`の関連線は誤りだったため削除した）。

`sequence` は「このスナップショットが `domain_events` の何番目までを反映しているか」
を表す。復元時は「最新スナップショット + それ以降の `domain_events`」で理論上は
再構築できるが、本ADRで決定した通り通常の `findByKey` はRDB投影を使うため、
この復元経路は監査・障害復旧用のバックアップ手段として位置づける。

### §12 ER図の拡張: `prompts.row_version`（楽観ロック）

§6.3 手順2「楽観ロック（version列）でVERSION_CONFLICTを検出」に対応する列が
§12のER図に存在しない。加えて `prompt_versions.version` は既にSemVer文字列用の
列名として使われているため、ロック用カウンタに `version` という名前は使えない。
`prompts`（Aggregate Root）に `row_version BIGINT NOT NULL DEFAULT 0` を追加する。
ロックはAggregate単位（`Prompt` 全体の保存/復元の単位、§2.14）で行うため、
`prompt_versions` 側には追加しない。保存のたびに `row_version` をインクリメントし、
UPDATE時に `WHERE row_version = :expected` が0件更新ならVERSION_CONFLICTとする。

### Outbox: 本フェーズはテーブル追記までとし、Broker中継の実配線は対象外とする

§6.3手順3「Outboxパターンでの Broker 中継」のうち、本PRでは
「保存とイベント追記・Outboxテーブルへの追記が同一トランザクションであること」
までを実装対象とし、Outboxテーブルをポーリングして実際にKafka互換Brokerへ
配信するプロデューサ/ポーラー部分は対象外とする。理由:
Broker配信の実配線にはKafka互換クライアント設定・配信失敗時のリトライ/
再送方針など§6.3の範囲を超える追加の設計判断が必要で、CLAUDE.mdの
「巨大PRを作らない（目安800行以内）」を踏まえ別フェーズに切り出す方が
レビュー可能な粒度を保てるため。`outbox` テーブルは
`dispatched_at`（NULL＝未配信）を持たせ、配信処理を後から追加できる形にする。

```
entity outbox {
  * outbox_id : UUID <<PK>>
  --
  * event_id : UUID <<FK -> domain_events.event_id>>
  dispatched_at : TIMESTAMPTZ  ' NULL = 未配信
  * created_at
}
outbox }o--|| domain_events : event_id
```

### `PromptRepository.save` にイベント引数を追加

§3.4のInterface定義（疑似コード）は `save(prompt: Prompt): void // Aggregate単位・
イベント追記` としており、saveが状態保存とイベント追記の両方を担う設計意図は
読み取れるが、疑似コード（「言語非依存」と明記）はイベントをどう渡すかまでは
規定していない。P1時点の実装（ADR-0004/0005）では `Prompt` の各操作メソッド
（`publish`/`rollback`等）が発行イベントを `Pair<Prompt, List<PromptDomainEvent>>`
として返し、`Prompt` 自身は発行済みイベントを保持しない。そのため
`PromptRepository.save(prompt: Prompt): Prompt` のままでは、呼び出し側
（Application層、未実装）が「状態保存」と「イベント追記」を1トランザクションで
行う手段がない。

`save` のシグネチャを `save(prompt: Prompt, events: List<PromptDomainEvent> =
emptyList()): Prompt` に変更する。呼び出し側は `Prompt.publish(...)` 等が返す
`Pair` をそのまま展開して渡す想定。これはP2で必要になった、
復元経路以外のdomainインターフェース変更のため、実装前にユーザーに確認を取った
（本ADR記載の通り）。

### 実装クラス名: `EventStorePromptRepository`

設計書§7（コンポーネント図）が `PromptRepository <|.. EventStorePromptRepository`
と明記しているため、`prompt-engine-infrastructure` 側の実装クラス名は
`promptengine.infrastructure.persistence.EventStorePromptRepository` とする
（独自に`JdbcPromptRepository`等の別名を付けない）。

### `Prompt.rowVersion`: 楽観ロックのトークンをAggregateに持たせる

真の楽観ロック（"同時更新での衝突を検出する"）には、`findByKey` で読んだ時点の
バージョンを `save` 呼び出し側が持ち回り、書き込み時にDB側の現在値と突き合わせる
仕組みが要る。`Prompt`（domain）がバージョンを一切保持しない場合、
`findByKey` → （呼び出し側でAggregateを変更）→ `save` という2回の別呼び出しの間に
他の書き込みが割り込んでも、`save` 側にはそれを検出する情報が一切残らない
（`save`の直前にDBを読み直しても、その時点では常に一致してしまい検出にならない）。
これは実装上の工夫では埋められない、情報の欠落そのものである。

`Prompt` に `rowVersion: Long = 0`（フレームワーク非依存のプレーンな `Long`。
`@Version`等のアノテーションは付けない）を追加する。多くのDDD実装で
Aggregate自身の改訂番号はAggregateのライフサイクルメタデータの一部として
許容されており、永続化技術の漏出とは区別される。`findByKey`（`Prompt.restore`
経由）がDBの `row_version` から復元し、`save` はDB側の現在値と突き合わせて
不一致なら `VersionConflictException`（`prompt-engine-infrastructure`側で定義、
domainには追加しない）を投げ、成功時は `rowVersion` をインクリメントした
コピーを返す。

これも復元経路以外の追加domain変更のため、実装前にユーザーに確認を取った。

### §12 ER図の拡張: `prompt_versions.context_requirement`

`PromptVersion.contextRequirement: ContextRequirement?`（`scope`/`required`/
`optional`の小さなVO）に対応する列が§12のER図に存在しない。他の列のように
判断が分かれる論点ではなく単純な漏れのため、`prompt_versions` に
`context_requirement JSON`（NULL許容）を追加する。

### マージ前レビュー: §12 ER図とV1__init.sqlの1対1対応を確認し、見つかった差分を修正

実装完了後、`docs/PromptEngine_設計書.md` §12 のER図が実際に上記の変更を
反映しているか、および `V1__init.sql` と1対1で対応しているかを行レベルで
突き合わせて確認した。見つかった差分と対応:

1. **`domain_events` がDomainEvent封筒8項目のうち3項目（`aggregateType` /
   `actor` / `traceId`）を欠いていた。** `promptengine.domain.event.DomainEvent`
   は8フィールド（`eventId, eventType, occurredAt, aggregateType, aggregateId,
   actor, traceId, payload`）だが、`domain_events` テーブルは
   `event_id, aggregate_id, sequence, event_type, payload, occurred_at` の
   6列（P1以前からの既存ギャップ）しか持たず、`EventStorePromptRepository`
   の実装も `actor`/`traceId`/`aggregateType` を書き込んでいなかった
   （`payload` にもこれらは含まれない）。監査証跡としてWHO/どのリクエストで
   発生したかが失われる実質的なバグのため、`aggregate_type` / `actor` /
   `trace_id` 列を追加し、`EventStorePromptRepository.appendEvents` を
   修正して書き込むようにした。`trace_id` にインデックスも追加する
   （設計書§2.15「相関ID（traceId/promptKey/version）」）。
2. **`domain_events }o--|| prompts : aggregate_id` の関連線が誤り。**
   `domain_events` は全Bounded Context共通のEvent Storeであり、Prompt以外の
   Aggregate（ReviewCase、Experiment等）のイベントも将来的に同じテーブルに
   追記される想定（`audit_logs` が特定テーブルへのFKを持たないのと同じ理由）。
   にもかかわらずV1__init.sqlでは`aggregate_id`にFK制約を付けておらず、
   ER図の関連線とSQLの実装が矛盾していた。ER図から当該関連線を削除し、
   SQL側の実装（FK無し）に合わせた。
3. **`outbox.dispatched_at` の型がER図では`TIMESTAMP`、実装では`TIMESTAMPTZ`
   だった。** Instant相当のUTC時刻を格納する列は全て`TIMESTAMPTZ`で統一する
   実装方針だったが、ER図側の記載を直し忘れていた。ER図を`TIMESTAMPTZ`に修正。
4. **`audit_logs.aggregate_id` の型がER図に明記されていなかった。**
   `domain_events.aggregate_id`との一貫性のため`UUID`と明記した
   （実装への影響なし、ドキュメントの明確化のみ）。
5. **`variants ||--o{ evaluation_records` の関連線が欠落していた。**
   `evaluation_records`エンティティ自体には`variant_id : UUID <<FK>>`が
   マークされているが、関連線一覧に対応する行がなかった（P1以前からの
   既存ギャップ、本ADRのスコープ外だが監査中に発見したため合わせて追加）。
6. **`prompt_tags`の複合主キー。** V1__init.sqlは`PRIMARY KEY (prompt_id,
   tag_id)`を設定しているが、ER図のPlantUML表記は両列を`<<FK>>`相当の`*`
   マークのみで`<<PK>>`タグを付けていない。中間テーブルとして標準的な
   構成であり実質的な矛盾ではないため、ER図・SQLとも変更しない。

## 影響範囲

- 設計書§12 のER図に `prompt_snapshots` / `outbox` エンティティと関連、
  `prompts.row_version` 列を追加
- 設計書§2.14 に、Command側の復元がRDB投影経由であり、`prompt_snapshots` +
  `domain_events` によるイベントリプレイは監査・障害復旧用の代替経路である旨を注記
- `prompt-engine-domain`:
  - `PromptVersion` / `Prompt` のプライマリコンストラクタを `internal` に変更し、
    両クラスに `@ConsistentCopyVisibility` を付与して `copy()` の可視性を
    コンストラクタに一致させる
  - `PersistenceApi`（`@RequiresOptIn` マーカー）を追加
  - `PromptVersionMemento` / `PromptMemento` を追加
  - `Prompt.Companion.restore(memento: PromptMemento): Prompt` を追加
- `prompt-engine-bootstrap` の `ArchitectureTest` に、`PersistenceApi` への依存が
  `promptengine.infrastructure.persistence..` に限定されることを検証するルールを追加
- `PromptRepository.save` のシグネチャに `events: List<PromptDomainEvent> =
  emptyList()` を追加（復元経路以外の追加domain変更、ユーザー確認済み）
- `Prompt` に `rowVersion: Long = 0` を追加し、`PromptMemento` にも
  `rowVersion: Long` を追加（復元経路以外の追加domain変更、ユーザー確認済み）
- 設計書§12 のER図に `prompt_versions.context_requirement` 列を追加
- マージ前レビューで発見した差分を修正（設計書§12 更新済み・V1__init.sql更新済み）:
  - `domain_events` に `aggregate_type` / `actor` / `trace_id` 列を追加し、
    `EventStorePromptRepository.appendEvents` を修正して書き込むようにした
  - `domain_events }o--|| prompts` の誤った関連線をER図から削除
  - `outbox.dispatched_at` のER図記載を `TIMESTAMP` → `TIMESTAMPTZ` に修正
  - `audit_logs.aggregate_id` の型（`UUID`）をER図に明記
  - `variants ||--o{ evaluation_records` の欠落していた関連線をER図に追加

## 参照

- [PromptEngine_設計書.md §12 / §2.14](../PromptEngine_設計書.md)
- [PromptEngine_ClaudeCode実装ガイド.md §6.3](../PromptEngine_ClaudeCode実装ガイド.md)
- [ADR-0004: 全状態遷移を監査可能にするため PromptWithdrawn / PromptDiscarded を追加する](0004-domain-events-for-state-transitions.md)
- [ADR-0005: publish は現在のPublished Versionを自動的にDeprecatedへ遷移させる](0005-publish-supersedes-current-published.md)
