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
domain_events }o--|| prompts : aggregate_id
prompt_snapshots }o--|| prompts : aggregate_id
```

`sequence` は「このスナップショットが `domain_events` の何番目までを反映しているか」
を表す。復元時は「最新スナップショット + それ以降の `domain_events`」で理論上は
再構築できるが、本ADRで決定した通り通常の `findByKey` はRDB投影を使うため、
この復元経路は監査・障害復旧用のバックアップ手段として位置づける。

## 影響範囲

- 設計書§12 のER図に `prompt_snapshots` エンティティと関連を追加
- 設計書§2.14 に、Command側の復元がRDB投影経由であり、`prompt_snapshots` +
  `domain_events` によるイベントリプレイは監査・障害復旧用の代替経路である旨を注記
- `prompt-engine-domain`:
  - `PromptVersion` / `Prompt` のプライマリコンストラクタを `internal` に変更
  - `PersistenceApi`（`@RequiresOptIn` マーカー）を追加
  - `PromptVersionMemento` / `PromptMemento` を追加
  - `Prompt.Companion.restore(memento: PromptMemento): Prompt` を追加
- `prompt-engine-bootstrap` の `ArchitectureTest` に、`PersistenceApi` への依存が
  `promptengine.infrastructure.persistence..` に限定されることを検証するルールを追加

## 参照

- [PromptEngine_設計書.md §12 / §2.14](../PromptEngine_設計書.md)
- [PromptEngine_ClaudeCode実装ガイド.md §6.3](../PromptEngine_ClaudeCode実装ガイド.md)
- [ADR-0004: 全状態遷移を監査可能にするため PromptWithdrawn / PromptDiscarded を追加する](0004-domain-events-for-state-transitions.md)
- [ADR-0005: publish は現在のPublished Versionを自動的にDeprecatedへ遷移させる](0005-publish-supersedes-current-published.md)
