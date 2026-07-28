# ADR-0009: CompositionServiceの参照解決基盤（キーグラフDFS・SemVer範囲・Status検証・CompiledPrompt）

## ステータス

Accepted

## コンテキスト

P3c（`docs/PromptEngine_ClaudeCode実装ガイド.md` §6.4関連スコープ）でCompositionServiceを
実装するにあたり、着手前に5点の方針をユーザーに確認した（P1/P2/P3bで確立した
「非自明なドメイン解釈はADR化してから実装する」運用を踏襲、
[[project_prompt_engine_p3b_template_fragment|P3bメモ]]参照）。確認の過程で、
ADR-0008の決定と設計書§15.3の間に1つの実務的なギャップが見つかったため、あわせて
本ADRで解消する。

### 発見したギャップ: extendsのVersion範囲を持つ場所が無い

設計書§15.1/§15.3は `extends: <templateKey>[@versionRange]` と定義しており、
`extends`はPrompt・Template双方のDSLフロントマターに現れる（§15.1のPrompt例、
§4.3のTemplate不変条件）。しかしADR-0008の決定1は
「`TemplateVersion.extendsKey`はextends先の`TemplateKey`のみを保持し、範囲文字列は
保持しない」としており、`PromptVersion`に至っては`extends`に対応するフィールドが
一切無い（`content: PromptContent`という生DSLソース文字列の中にしか存在しない）。

このままでは、CompositionServiceがextendsのVersion範囲を解決するには、構造化
フィールドだけでは情報が足りず、所有者（Prompt/Template）の`content.source`を
CompositionService自身が実行時に再パースして範囲文字列を復元する必要が生じる。
これは動作はするが、（a）Aggregateが「知っているはず」の情報をCompositionServiceが
実行時に毎回再導出する構造になり、（b）DBの構造化列と生DSLテキストの間に
「一致しているはず」という暗黙の前提が生まれ、それを保証する仕組みが無いという
2つの弱点を持つ。

## 決定

### 1. extends参照は「key + range」の完全な形でAggregateに保持する（ADR-0008決定1の一部を追記改訂）

新設VO `promptengine.domain.shared.VersionRange`（`Latest` / `CaretMajor(major)` /
`Exact(SemVer)`の3値、`parse(text: String?)`ファクトリと`toRangeText(): String?`を持つ
純粋な値型）と、`promptengine.domain.template.ExtendsRef(key: TemplateKey, range: VersionRange)`
を新設する。

- `TemplateVersion.extendsKey: TemplateKey?` を `TemplateVersion.extends: ExtendsRef?` に置き換える。
- `PromptVersion`に新規フィールド `extends: ExtendsRef?`（デフォルト`null`）を追加する
  （Prompt側は今回が初めての導入）。
- `NewTemplateVersion`/`NewPromptVersion`/`TemplateVersionMemento`/`PromptVersionMemento`も
  同様に`extends`（`ExtendsRef?`）を持つ。
- 自己参照禁止の不変条件（`Template.init`）は`extends?.key == 自分自身のkey`で判定する
  （rangeの値に関わらず、同一Templateキーへの参照であれば自己参照とみなす）。
- スキーマ変更: `template_versions.extends_key`はそのまま、`extends_version_range VARCHAR`を
  追加する。`prompt_versions`には`extends_key VARCHAR`・`extends_version_range VARCHAR`を
  新規追加する（Prompt側は初導入のため2列とも新規）。Flyway `V3__extends_version_range.sql`。
  設計書§12のER図を対応するテーブル定義に更新する（本ADRの「影響範囲」参照）。

「保存された参照 == DSLソースをパースした結果」という整合性は、次の2段構えで保証する。

1. `prompt-engine-core`に、フロントマターの生`extends`文字列
   （例: `"templates/base-assistant@^2"`）を`ExtendsRef`へ変換する単一の関数
   （`ExtendsFieldMapper.parse`、実装は本ADRの実装スコープであるPR1に含む）を用意し、
   これを「DSLテキストから`ExtendsRef`を作る唯一の経路」とする。
2. `PromptDslParser`で実際にDSLソースをパースして得たフロントマターの生`extends`文字列を
   同じ関数に通した結果が、期待する`ExtendsRef`と一致することをテストで固定する
   （ラウンドトリップテスト）。

なお、「DSLをアップロード/保存する際に自動的にAggregateへ`ExtendsRef`を設定する」
という取り込みパイプライン自体（Template/Prompt Authoring API）はまだ存在しない
（P3bはドメイン+永続化のみ、P9でREST APIが追加される予定）。そのパイプラインが
実装される際は、必ずこの`ExtendsFieldMapper`を経由すること。個別に`extends`文字列を
解析するコードを重複して作らないこと。

CompositionService自身は、この決定により**再パースによる復元を行わない**
（extendsの key/range は常にAggregateの構造化フィールドから直接読む）。
これはユーザーからの明示指示であり、CompositionServiceの実装をシンプルに保つ
（DSLパーサへの依存箇所を「Template/Fragmentのbodyを展開する時」だけに限定する）
という利点もある。

### 2. 深さ上限「5」は、extends/import/include/macro解決チェーンを通算した上限と解釈する（設計書§15.5を明確化）

設計書§15.5は「循環禁止、深さ上限5」とInclude仕様の節に書かれているが、§15.3が
「Composition解決順: extends → import → include → macro展開」と1つの解決プロセスとして
定義していること、既存の設計書のどこにも extends 単体の深さ上限が別途定義されていない
ことから、**この「5」は個々の機構（extendsのみ／includeのみ）ではなく、1つのCompiledPrompt
を生成するための解決チェーン全体を通算した深さ上限**であると解釈する。

つまり、Prompt→Template→Template→Fragment→Fragmentのように機構をまたいで参照が連なる
場合も、全体で深さ5を超えたら`CompositionDepthExceededException`を投げる。これは
P3aのパーサ側ネスト深さ上限（`PromptDslParserConfig.maxNestingDepth`、既定8、
DSL本文内の`{{#if}}/{{#each}}/{{#block}}`構文木の入れ子数）とは別概念であり、
`CompositionDepthExceededException`のKDocと設計書§15.5に、両者が別概念であることを
1行で明記する。

### 3. Nested Prompt（`{{> prompt:key@range }}`）は本フェーズのスコープ外とする

3aの`IncludeNode`のKDocは「`target`がどの形式か（alias/fragmentKey/`prompt:`）の判定は
3cの責務」としているが、本タスクの実装スコープには明示的に含まれていない。ユーザーの
確認の結果、今回は明示的にスコープ外とし、`target`が`"prompt:"`で始まる`IncludeNode`を
CompositionServiceが検出した場合は、専用の`NestedPromptNotSupportedException`を投げる
（他の未定義動作に倒さない）。GitHub Issueで次フェーズへの回収を追跡する
（本ADRの「参照」参照）。

### 4. 循環検出はキー参照グラフ上のDFSとする（設計書§4.5により追認）

設計書§4.5は「CompositionService | Merge/Import解決、循環検出（**DFS**）、CompiledPrompt生成」
と明記しており、AST走査方式ではなくキー参照グラフ（`(kind, key, resolvedVersion)`の
有向グラフ）上のDFSであることは設計書自体から読み取れる。ノードは
`(TEMPLATE|FRAGMENT, key, resolvedVersion)`のタプルとし、辺はextends/import/include参照。
DFSは祖先パス（現在の解決チェーン）をスタックで保持し、

- スタック中に既出のノードへ再訪 → `CircularDependencyException`
- 既出ではないがスタック長が上限（5、決定2参照）を超過 → `CompositionDepthExceededException`
- 祖先ではない別分岐からの再訪（正当な多重取込、§15.4の「同一Fragmentの多重取込を1回に
  正規化する」に対応）→ 循環ではない。一度解決した`(kind, key, resolvedVersion)`は
  メモ化し、再利用する（決定性の担保にも寄与）。

### 5. SemVer範囲解決とCompiledPromptの依存一覧

`VersionRange`（決定1）に加え、解決結果を保持する`promptengine.domain.composition.ResolvedDependency`
（sealed: `TemplateDependency`/`FragmentDependency`）を新設し、各要素は
`{ key, requestedRange: VersionRange, resolvedVersion: SemVer, status: PublicationState, contentHash: String }`
を持つ。範囲解決は「対象Aggregateの全Versionのうち、`range`にマッチし、かつ
Status条件（後述）を満たすものの中から最大の`SemVer`を選ぶ」という純粋関数であり、
同一リポジトリ状態に対しては常に同じ結果を返す（決定性）。これをテストでは、
固定内容のFake `TemplateRepository`/`FragmentRepository`に対して同じ入力を2回解決し、
結果が構造的に等しい（`CompiledPrompt`はdata classなので`==`で比較可能）ことに加え、
複数のPublished Versionが同時に存在する状態で常に最大が選ばれることを確認する形で
固定する。

`promptengine.domain.composition.CompiledPrompt`は`{ body: List<PromptAst>, dependencies:
List<ResolvedDependency>, variables: List<VariableDefinition>, contextRequirement:
ContextRequirement? }`を持つVOとして定義する。ただし本ADRが対象とするPR1（後述）では
型定義と依存解決部分までを実装し、`body`への実際のAST合成（extendsマージ・super()・
include展開・macro展開）はPR2（`feat/p3c2-composition-rules`）で行う。

### 6. Status検証の責務分界

CompositionServiceは解決時点のゲートを持つ：範囲の候補は原則`Published`のみ、
`CompositionMode.COMPILE_ONLY`の場合のみ`Draft`も候補に含める（設計書§2.10の
Compile-onlyモードの扱いに対応）。該当なしなら`TemplateReferenceNotFoundException`/
`FragmentReferenceNotFoundException`（→ 設計書§13.3の`TEMPLATE_NOT_FOUND`/
`FRAGMENT_NOT_FOUND`）。Validation Engine（P5、未実装）の`DependencyValidation`
Rule（設計書§2.10）は、`CompiledPrompt.dependencies[].status`を読むだけで
Draft参照等をseverity付きの`ValidationReport`所見に変換する側であり、
リポジトリへの再照会は行わない。

### 7. エラー種別とHTTPコードの写像

`CircularDependencyException`/`CompositionDepthExceededException`/
`CompositionSizeExceededException`/`TemplateReferenceNotFoundException`/
`FragmentReferenceNotFoundException`/`DraftReferenceNotAllowedException`/
`MacroRecursionException`/`NestedPromptNotSupportedException`を
`promptengine.domain.composition`にKotlin例外として個別に用意する。設計書§13.3の
エラーコード表には`CIRCULAR_DEPENDENCY`/`TEMPLATE_NOT_FOUND`/`FRAGMENT_NOT_FOUND`のみが
定義されており、深さ超過・サイズ超過・Draft参照拒否・マクロ再帰・Nested Prompt未対応に
対応するHTTPコードは存在しない。本フェーズはdomain/core層のみを対象とし、
`GlobalExceptionHandler`（`prompt-engine-interface`）は対象外であるため、HTTPコードへの
写像は先送りする。ADR-0008が Template/Fragment のDomain Event未発行を Issue #15 で
追跡したのと同様、本件も既知のギャップとしてGitHub Issueで追跡する
（本ADRの「参照」参照）。

### 8. PR分割

実装量が大きいため、ユーザー確認の上で2PRに分割する。

- **PR1**（`feat/p3c1-reference-resolution`、本ADRが対象）: 決定1（`ExtendsRef`/
  `VersionRange`とAggregate変更・マイグレーション）、決定4〜7（キーグラフDFS・
  SemVer範囲解決・Status検証・`ResolvedDependency`・`CompiledPrompt`の型定義・
  例外階層）。
- **PR2**（`feat/p3c2-composition-rules`、PR1マージ後にPR1へ依存する形で着手）:
  `CompositionService`（domain Interface）本体・extendsマージ+`super()`・
  importエイリアス解決・include展開+変数束縛・macro展開+再帰検出・
  `CompiledPrompt.body`への実際のAST合成。

## 影響範囲

- 設計書§12のER図: `template_versions`に`extends_version_range`を追加。
  `prompt_versions`に`extends_key`・`extends_version_range`を追加。
- 設計書§15.5: 深さ上限5が「extends/import/include/macro解決チェーンの通算」である旨を明記。
- `prompt-engine-domain`:
  - `promptengine.domain.shared.VersionRange`を新設
  - `promptengine.domain.template.ExtendsRef`を新設、`TemplateVersion.extendsKey`を
    `extends: ExtendsRef?`に置換（`Template`/`NewTemplateVersion`/`TemplateVersionMemento`も追随）
  - `promptengine.domain.prompt.PromptVersion`に`extends: ExtendsRef?`を追加
    （`Prompt`/`NewPromptVersion`/`PromptVersionMemento`も追随）
  - `promptengine.domain.composition.*`（`CompositionMode`/`ResolvedDependency`/
    `CompiledPrompt`/例外群）を新設
- `prompt-engine-infrastructure`:
  - `db/migration/V3__extends_version_range.sql`を新設
  - `JdbcTemplateRepository`（`extends_key`+`extends_version_range`の読み書き）、
    `EventStorePromptRepository`（`prompt_versions`に`extends_key`+
    `extends_version_range`列を追加した読み書き）を更新
  - `ExtendsRef`のDBコーデック（`VersionRange.toRangeText()`/`parse()`利用）を追加
- `prompt-engine-core`:
  - `promptengine.engine.compiler.ExtendsFieldMapper`（フロントマター生`extends`文字列→
    `ExtendsRef`の唯一の変換経路）を新設
  - `promptengine.engine.compiler`にキーグラフDFS・SemVer範囲解決・Status検証を行う
    参照解決コンポーネント（PR2の`CompositionService`実装が内部的に利用する）を新設
- `tests/integration`: `JdbcTemplateRepositoryIntegrationTest`/
  `EventStorePromptRepositoryIntegrationTest`に`extends`往復のケースを追加

## 参照

- [PromptEngine_設計書.md §2.10 / §4.3 / §4.5 / §12 / §13.3 / §15.1 / §15.3 / §15.4 / §15.5](../PromptEngine_設計書.md)
- [PromptEngine_ClaudeCode実装ガイド.md §6.4](../PromptEngine_ClaudeCode実装ガイド.md)
- [ADR-0008: Template / Fragment Aggregateのドメインモデルと永続化方針](0008-template-fragment-domain-model.md)（本ADRが決定1を追記改訂）
- GitHub Issue: 「Nested Prompt（`{{> prompt:key }}`）を実装する」（tech-debt）
- GitHub Issue: 「Composition関連エラー種別のHTTPコードを設計書§13.3に追加する」（tech-debt）
