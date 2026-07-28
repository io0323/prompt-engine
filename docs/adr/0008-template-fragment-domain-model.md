# ADR-0008: Template / Fragment Aggregateのドメインモデルと永続化方針

## ステータス

Accepted

## コンテキスト

P3b（`docs/PromptEngine_ClaudeCode実装ガイド.md` §6.4関連スコープ）でTemplate/Fragment
Aggregateとその永続化を実装するにあたり、着手前に4点の方針をユーザーに確認した
（P1/P2の[[project_prompt_engine_p1_domain_model|P1メモ]]・
[[project_prompt_engine_p2_persistence|P2メモ]]で確立した「非自明なドメイン解釈は
ADR化してから実装する」運用を踏襲）。確認の過程で、設計書自体に2つの矛盾が
見つかったため、あわせて記録する。

### 発見した矛盾1: §4.3とER図（§12）のVersioning構造の不一致

§4.3は `Template | TemplateVersion | 循環継承禁止` `Fragment | FragmentVersion |
循環Include禁止` とし、Prompt/PromptVersionと同じ「1つのAggregateが複数Versionを
子Entityとして持つ」構造を明記している。

しかし§12のER図は `templates`/`fragments` を `template_key`/`fragment_key` が
`UNIQUE`な単一行テーブルとし、`version`列を1本だけ持つ構造だった
（`prompt_versions`のような子テーブルが存在しない）。この形では、同一キーに
つき同時に存在できるVersionは常に1つだけになる。

一方、§15.4（Import仕様）は次の例をすでに仕様として持っている:

```yaml
imports:
  - alias: safety
    ref: fragments/safety-policy@^2    # SemVer範囲。^2=2.x最新Published
```

`^2`（2.x系の最新Published）という参照は、同一キーの複数のPublished Versionが
**同時に存在すること**を前提としている。単一行構造ではこの仕様を満たせない。
これはADR-0006が見つけた一連のER図の抜け漏れ（`row_version`/`outbox`/
`prompt_snapshots`等）と同種の、意図的な設計判断ではなく単純な欠落と判断した。

### 発見した矛盾2: §14にTemplate/Fragmentのイベントが1件も無い

§14のイベント一覧はPrompt/ReviewCase/Compiler/各Engine/Experiment/Plugin/
Cache Invalidatorのイベントのみを列挙しており、Template/Fragmentに対応する
イベント（例: `TemplatePublished`）は存在しない。CLAUDE.mdは
「設計書にない...イベントを勝手に追加しない。必要なら先にADRを起こして提案する」
と定めているため、Prompt同様のイベント発行付き State パターンをそのまま
Template/Fragmentに適用すると、未提案のイベント種別を実装してしまうことになる。

## 決定

### 1. Versioning構造: 複数Version子Entity方式を採用する（§4.3を正とする）

`Template`/`Fragment` を、`Prompt`/`PromptVersion` と同じ「ヘッダAggregate +
Version子Entityのリスト」構造にする。§12のER図を次のように変更する
（本ADRの「影響範囲」参照、実装は `V2__template_fragment.sql`）:

- `templates`/`fragments`: `prompts` と同型のヘッダ行（`*_key` UNIQUE、
  `row_version`、`created_by`/`created_at`/`updated_at`）に変更し、
  `body`/`version`/`extends_key`列を除去する。
- `template_versions`/`fragment_versions`: `prompt_versions` と同型の子テーブルを
  新設する（`UNIQUE(親id, version)`）。`extends_key`は`template_versions`側に移す
  （extendsは特定のTemplate**Version**の属性であり、Template全体の属性ではないため）。
- `template_variable_defs`/`fragment_variable_defs`: `variable_defs`と同型の
  子テーブルを新設する。既存の`variable_defs`は`prompt_versions`にのみFKを
  張っているため、これを汎用化（owner種別列を追加する等）するのではなく、
  既存のPrompt向けテーブルはそのまま残し、Template/Fragment用に別テーブルを
  用意する（既存FK制約を壊さない・他Aggregateとの参照整合性を型で保つ）。

`extends`のVersion範囲（`@^2`等）の解釈・解決は §6.4 の元々のスコープ除外通り
3c（CompositionService）に委ねる。本フェーズでは`TemplateVersion.extendsKey`に
extends先の`TemplateKey`のみを保持し、範囲文字列は保持しない
（「今回はextends先のキーを保持するところまで」という元々のスコープ合意通り）。

### 2. ライフサイクル: 簡略版3状態（Draft/Published/Archived）を採用する

Promptの6状態（`LifecycleState`、Review/Approval付き）は再利用しない。
§4.3にTemplate/Fragmentの承認フローに関する不変条件が存在せず、§4.5の
Domain Service一覧にも承認関連のServiceが無いため、Review/Approved相当の
状態を導入する設計書上の根拠が無い。

`promptengine.domain.shared.PublicationState`（Draft/Published/Archived、
`publish()`/`archive()`のみ）をTemplate/Fragment共通の状態機械として新設する。
`Prompt.LifecycleState`とは意図的に別クラスとし、Promptの遷移規則を変更しない。

`Deprecated`相当の状態は導入しない。§2.10のDependencyValidationも
「Published以外の参照を拒否」とだけ定めており、Deprecated/Archivedを区別する
必要が無いため。

Published化された内容を書き換えるAPI（Promptの`withContent`相当）も設けない。
内容の変更は常に新しいVersionの追加（`newVersion`）で表現する。これにより
「Published内容はImmutable」という別途の不変条件を新設する必要が無くなる。

### 3. Domain Event: 本フェーズでは発行しない

Template/FragmentのAggregate操作（`create`/`newVersion`/`publish`/`archive`）は
イベントを返さない。§14に対応イベントが定義されていないこと、および元々の
スコープ確認で「P2の**復元経路**パターン（Memento + `@PersistenceApi`）を
そのまま適用する」と明示されており、Event Store/Outbox統合までは要求されて
いないことの両方による。

イベントが無いため、`EventContext`（actor/traceId/occurredAt）も
Template/Fragmentのドメインメソッドには一切登場しない。永続化層が書き込む
`created_by`は、Promptの無イベント経路（`EventStorePromptRepository`の
`DEFAULT_ACTOR`）と同じ固定値 `"system"` を用いる。

Template/FragmentのDomain Eventが今後必要になった場合
（例: 索引更新やキャッシュ無効化のトリガとして）は、ADR-0004がReviewCase
イベントを切り出したのと同様、別ADR + §14更新 + GitHub Issueで提案する
（本ADRでは提案しない）。

#### イベント未発行によって生じる既知の欠落（2件）

本フェーズでTemplate/FragmentがDomain Eventを一切発行しないことにより、
現時点で次の2つの欠落が生じている。どちらも「後で気づく」のではなく、
本ADRの時点で既知のトレードオフとして記録する。

1. **Audit Logに記録が残らない。** `audit_logs`（§12）はDomain Event（Event Bus
   経由でAudit Engineが購読、§7コンポーネント図）を発生源として書き込まれる想定
   だが、Template/Fragmentはイベントを発行しないため、`publish`/`archive`等の
   操作はAudit Logに一切記録されない。NFR-006「Audit Logは追記専用・保持期間
   設定可」の対象からTemplate/Fragmentの操作history全体が抜け落ちている状態
   であり、本フェーズ時点でも実害がある（監査証跡が無い）。
2. **CompiledPromptキャッシュを無効化する契機が存在しない。** §16の拡張ポイント
   #9（Cache、`PromptCache`）は「Version公開イベントで呼出」
   （`invalidateByPrompt(key): void // Version公開イベントで呼出`、§3.4）を
   前提としている。Template/Fragmentがイベントを発行しない現状では、
   TemplateやFragmentのPublish/Archiveをトリガに依存Prompt側の
   CompiledPromptキャッシュを無効化する仕組みが存在しない。本フェーズ時点では
   CompiledPromptキャッシュ自体が未実装（3c以降のスコープ）のため実害は無いが、
   **キャッシュを導入するフェーズより前に必ず解消する必要がある** ──
   さもなければ、Draft相互参照のCompile-onlyモード（§2.10）から
   Templateが後からPublishされた場合や、Publish済みTemplate/Fragmentの
   新Versionがdeprecate/archiveされた場合に、依存Prompt側が古い
   CompiledPromptを無期限に配信し続けるstale cacheバグを生む。

この2件はGitHub Issue #15（tech-debt、「Template / Fragmentの Domain Eventを
設計書§14に追加し実装する」）で追跡する。

### 4. Aggregate内蔵の循環検出はTemplateの自己extendsチェックのみとする

- **Template**: `TemplateVersion.extendsKey`は構造化フィールドとして保持している
  ため、Aggregateの`init`ブロックで「`extendsKey == 自分自身のkey`」を検証できる
  （直接の自己参照、長さ1の循環のみ検出可能）。`A extends B extends A`のような
  長さ2以上の循環は、他AggregateをたどるDBアクセスが必要でありAggregate単体では
  検出不可能なため、3cのCompositionService（DFS）に委ねる。
- **Fragment**: `{{> }}`によるInclude参照は、DSL本文（`body: TEXT`）の中に
  埋め込まれたテキストであり、Fragment Aggregateは構造化されたInclude先の
  リストを一切持たない（`§12`のfragmentsテーブルにそもそも該当列が無い）。
  本文をパースしてInclude先を抽出する責務は`prompt-engine-core`のParser/
  CompositionServiceであり、CLAUDE.mdの規約
  （`prompt-engine-domain`は他のいかなるモジュールにも依存しない）により
  domainモジュールがパーサ出力に依存することはできない。したがって
  Fragment Aggregateは自己Includeを含め、循環検出を一切行わない
  ── 自己Includeの検出さえも3cのCompositionServiceに完全に委ねる。

### 5. `@PersistenceApi`マーカーを`promptengine.domain.shared`へ移動し、3アグリゲートで共有する

ADR-0006で`promptengine.domain.prompt.PersistenceApi`として導入した
`@RequiresOptIn`マーカーを、`Template.restore`/`Fragment.restore`でもそのまま
再利用する。Prompt専用パッケージに置いたままでは、`domain.template`/
`domain.fragment`からの参照がArchUnitルール
（「`PersistenceApi`への依存は`domain.prompt`と`infrastructure.persistence`に
限定される」）に違反してしまうため、`promptengine.domain.shared.PersistenceApi`
に移動し、ArchUnitルールの許可パッケージを`promptengine.domain..`
（Prompt/Template/Fragmentのどのアグリゲートからも復元経路として使える）+
`promptengine.infrastructure.persistence..`に広げる。

これは「P2で確立した復元経路パターンが2つ目・3つ目のAggregateでも本当に
通用するか」を検証する目的そのものであり、機械的で低リスクな移動
（ファイル移動+import修正のみ、ロジック変更なし）である。

`VersionConflictException`（永続化技術に紐づく例外、domainには置かない）は
Prompt用を変更せず、`TemplateVersionConflictException`/
`FragmentVersionConflictException`をそれぞれ新設する（3クラスの重複を許容し、
既存のPrompt実装・テストへの副作用を避ける）。

### 6. Infrastructure実装クラス名は`JdbcTemplateRepository`/`JdbcFragmentRepository`とする

`EventStorePromptRepository`という名前は§3.3が明記した固有名だが、
Template/FragmentはEvent Store/Outboxに一切書き込まないため、同じ接頭辞を
付けると実装内容と名前が一致しない。§3.3・§7にTemplate/Fragment実装の
固有名の指定が無いため、実態（`NamedParameterJdbcTemplate`によるJDBC実装、
Event Sourcingなし）に即した名前を新たに付ける。

## 影響範囲

- 設計書§12のER図: `templates`/`fragments`を`prompts`と同型のヘッダ行に変更し、
  `template_versions`/`fragment_versions`/`template_variable_defs`/
  `fragment_variable_defs`を追加
- `prompt-engine-domain`:
  - `promptengine.domain.prompt.PersistenceApi`を
    `promptengine.domain.shared.PersistenceApi`へ移動
    （`Prompt.kt`/`PromptTest.kt`のimportを更新）
  - `promptengine.domain.shared.PublicationState`/
    `InvalidStateTransitionException`を新設
  - `promptengine.domain.template.*`（`TemplateKey`/`TemplateContent`/
    `NewTemplateVersion`/`TemplateVersion`/`Template`/`TemplateMemento`/
    `TemplateVersionMemento`/`TemplateVersionNotFoundException`/
    `TemplateRepository`）を新設
  - `promptengine.domain.fragment.*`（Template側と対称、`extendsKey`を除く）
    を新設
- `prompt-engine-infrastructure`:
  - `JdbcTemplateRepository`/`JdbcFragmentRepository`、
    `TemplateVersionConflictException`/`FragmentVersionConflictException`、
    Row Codec拡張関数を新設
  - `db/migration/V2__template_fragment.sql`を新設
- `prompt-engine-bootstrap`の`ArchitectureTest`:
  `PersistenceApi`関連ルールの対象パッケージを`domain.prompt`から
  `domain..`に拡大
- `tests/integration`: `JdbcTemplateRepository`/`JdbcFragmentRepository`の
  Testcontainers統合テストを追加

## 参照

- [PromptEngine_設計書.md §4.3 / §12 / §14 / §15.4 / §16](../PromptEngine_設計書.md)
- [PromptEngine_ClaudeCode実装ガイド.md §6.4](../PromptEngine_ClaudeCode実装ガイド.md)
- [ADR-0004: 全状態遷移を監査可能にするため PromptWithdrawn / PromptDiscarded を追加する](0004-domain-events-for-state-transitions.md)
- [ADR-0006: 永続化層からの復元は Memento + @PersistenceApi opt-in に限定する](0006-persistence-restore-path.md)
- [ADR-0007: sensitive=trueの変数はリテラルのdefaultを持てない](0007-sensitive-variable-no-literal-default.md)
- [GitHub Issue #15: Template / Fragment の Domain Event を設計書§14に追加し実装する](https://github.com/io0323/prompt-engine/issues/15)
