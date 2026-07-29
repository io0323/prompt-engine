# ADR-0011: VariableDefinition.source の導入、ContextRequirementの複数形化、Secret解決失敗の分類

## ステータス

Accepted

## コンテキスト

P4（Resolver）実装にあたり、設計書と現行実装の間に3つの未決事項が見つかった。

### 1. VariableDefinitionにsourceがない

設計書§2.8は「全Variableは `type`、`required`、`default`、`constraints`、`sensitive` を宣言する」
と述べる一方、§15.2のDSLサンプルは全変数に `source`（static|runtime|secret|environment|user|workflow）を
明記している。現行の`VariableDefinition`（P1で実装）はこの`source`を持たない。

`source`を持たないと、P4の6種Resolver Chain（§2.8「Explicit Parameter → Static → User →
Workflow → Environment → Secret」）は変数名だけを頼りに各Resolverのバッキングストアを
総当たりで探すしかない。これは以下の問題を生む:

- 呼出パラメータにたまたま同名の値がなくても、UserストアとWorkflowストアの双方に
  たまたま同名の値が存在すれば、宣言者の意図と無関係にどちらが先勝ちするかは
  Resolver Chainの実装順序だけで決まってしまう（「宣言された解決経路」という
  概念が型として存在しないため、偶然の一致による誤解決を構造的に排除できない）。
- `source: secret`変数が、SecretResolverを経由せずEnvironmentResolver等で
  誤って解決されてしまう経路を構造的に防げない。

### 2. ContextRequirementが単数

§6クラス図（913行目）は`PromptVersion.contextReqs: List<ContextRequirement>`と定義するが、
現行の`PromptVersion`/`CompiledPrompt`は`contextRequirement: ContextRequirement?`（単数・nullable）
しか持たない。`ContextRequirement`自体は「1スコープ分のrequired/optional」を表すVOであり
（§4.4「scope + required/optional + 参照path一覧」）、単数フィールドではPromptが同時に
複数スコープ（例: system + user）を宣言できない。

P4の`ContextResolverImpl`は「宣言された各scope」をループしてマージする
（§5.4シーケンス）。P5の「未宣言スコープへの参照はValidationエラー」（§2.7）も、
宣言済みスコープの全リストがなければ判定しようがない。単数のままではP4・P5双方が
設計書どおりに実装できない。

### 3. Secret解決失敗の扱いが未分類

SecretResolverが失敗する経路には性質の異なる2種類がある。

- (a) `sensitive=true`の変数に対応するSecretがSecret Managerに設定されていない
  （「未設定」という正常系の異常）。
- (b) Secret Manager自体への到達性がない・認証エラーが起きた等の障害
  （PEから見て外部依存の障害であり、変数固有の問題ではない）。

設計書§13.3のエラーコード表には`VARIABLE_UNRESOLVED`のみが定義されており、Secret固有の
コードは存在しない。CLAUDE.mdは「設計書にないエラーコードを勝手に追加しない」と定めるため、
(a)を新コードにする選択肢はない。一方、(a)と(b)を無区別に同じ`VARIABLE_UNRESOLVED`へ
畳み込むと、Secret Manager全体が落ちているときに「個々の変数が未設定」という
誤ったエラーメッセージを返すことになり、障害調査を妨げる。

### 4. Resolver系Interfaceがcoreに置かれ、applicationから参照できない

P4実装当初、`VariableResolver` / `ContextResolver`（拡張ポイントInterface）と、
Chain全体のファサードに相当する型を、実装（6種標準Resolver・`ContextResolverImpl`）と
まとめて`prompt-engine-core`（`promptengine.engine.resolver`）に置いていた。

しかし`CompositionService`（設計書§4.5「Domain Service」）は「Interfaceはdomain、
実装はcore」という配置になっており（`domainはDSL Parserに依存できないため`、ADR-0010決定9）、
これは`prompt-engine-application`が`prompt-engine-domain`にしか依存できないという
モジュール依存の絶対規約（CLAUDE.md、`ArchitectureTest`で機械強制）から導かれる配置である。
Resolver系Interfaceを`core`に置いたままだと、P8（Pipeline Orchestrator）が
Variable/Context解決を呼び出す際に`prompt-engine-application`が`prompt-engine-core`へ
依存せざるを得ず、規約に違反する。`CompositionService`と異なる配置にする理由（domainが
依存できない外部技術へのInterface依存）がResolver系には存在しないため、これは設計上の
一貫性の欠如であり、素直に`CompositionService`と同じ形に揃えるべきである。

## 決定

### 1. VariableSourceを追加する

`VariableDefinition`に`source: VariableSource`（`STATIC` / `RUNTIME` / `SECRET` /
`ENVIRONMENT` / `USER` / `WORKFLOW`、§2.8の6種に対応）を追加する。デフォルトは
`STATIC`（DSLでもっとも一般的な「リテラル既定値」のケースが最小記述で書けるようにするため）。

不変条件を追加する: `require(!(source == VariableSource.SECRET && !sensitive))`。
`source == SECRET`の変数は必ず`sensitive == true`でなければならない
（逆は要求しない。`sensitive == true`だが`source`が`SECRET`でない、という組み合わせ自体は
将来的にありうるため禁止しない）。ADR-0007の「`sensitive == true`なら`default`は
`null`でなければならない」不変条件はそのまま維持する。

Resolver Chainの各Resolverは「`def.source`が自分の担当種別と一致する変数」のみを
解決対象とする。ただしExplicit Parameterによる上書きは`source`によらず常に最優先
（§2.8「同名変数は先勝ち（明示パラメータ最優先）」）とし、`ExplicitParameterResolver`は
`source`を見ない。これにより、宣言された解決経路と異なるストアからの偶然の解決が
構造的に起きなくなる。

スキーマ変更を伴うため、Flyway移行（V4）で`variable_defs` / `template_variable_defs` /
`fragment_variable_defs`の3テーブルに`source`列（`NOT NULL DEFAULT 'STATIC'`）を追加する。
設計書§2.8末尾の宣言フィールド一覧および§12 ER図に`source`を反映する（記述漏れの修正）。

### 2. ContextRequirementを複数形にする

`PromptVersion.contextRequirement: ContextRequirement?` を
`PromptVersion.contextRequirements: List<ContextRequirement>`（既定値`emptyList()`）に変更する。
同様に`NewPromptVersion` / `PromptVersionMemento` / `CompiledPrompt`も追随させる。
`ContextRequirement`自体の形（scope + required/optional）は変更しない。

永続化層は`prompt_versions.context_requirement`（JSON、単一オブジェクト）を
`context_requirements`（JSON、配列）へ列名変更する。P2時点でこのテーブルは
本番データを持たない（開発中）ため、データ移行は不要で列定義の変更のみ行う。

`CompositionServiceImpl`は現状どおり`promptVersion`自身の宣言のみを`CompiledPrompt`へ
引き継ぐ（Template/Fragmentの`contextRequirements`マージは行わない。
`TemplateVersion`/`FragmentVersion`はそもそも`contextRequirement`を持たないため、
本ADRの変更範囲では現状の挙動を変えない）。

### 3. Secret解決失敗を「未設定」と「Secret Manager障害」に分ける

- `SecretManagerAdapter.getSecret(name)`が`null`を返した場合（Secretが設定されていない）
  → `SecretResolver`は他のResolver同様に「未解決」として扱い、`required`ならば
  `VariableUnresolvedException`の未解決名一覧に含める。新規エラーコードは追加しない。
- `SecretManagerAdapter.getSecret(name)`が例外を投げた場合（Secret Manager自体への
  到達性・認証エラー等のインフラ障害）→ その例外を`VariableResolverChain`は捕捉せず
  そのまま呼び出し元へ伝播させる。`VARIABLE_UNRESOLVED`には混ぜない。HTTPコードへの
  写像はP9（REST API）で決定する（`CompositionException`の前例と同様、本フェーズは
  domain/core層のみを対象とする）。

この区別は`SecretManagerAdapter`と`SecretResolver`双方のKDocに明記し、両方の経路を
個別のテストで固定する。

### 4. Engine系のInterfaceはdomainに、実装はcoreに置く

`CompositionService`/`CompositionServiceImpl`と同じ形に揃える。

- `VariableResolver`（Interface）→ `domain.variable`
- `VariableResolverChain`（Chainのファサード、新設Interface。`fun resolveAll(definitions,
  request): BindingSet` のみを持つ）→ `domain.variable`
- `ContextResolver`（Interface）→ `domain.context`
- `PromptRequest`（上記3つのInterfaceが共通して参照するVO）→ `domain.shared`
  （`VariableResolver`/`VariableResolverChain`/`ContextResolver`いずれもdomainからしか
  参照できないため、これらがパラメータに取る`PromptRequest`もdomainに置かざるを得ない。
  variable/context双方から参照されるため`domain.shared`とする）
- 実装（6種標準Resolver、Chainの実装（`VariableResolverChainImpl`に改称）、
  `ContextResolverImpl`、7スコープ標準の`StandardContextResolver`）は
  `prompt-engine-core`（`promptengine.engine.resolver`）に残す

`ContextResolverImpl`自体（Context解決のオーケストレーション）にはdomain向けの
ファサードInterfaceを設けない。Variable側の`VariableResolverChain`と非対称になるが、
これはP8（Pipeline Orchestrator）でのApplication層からの呼び出し方式全体を設計する際に
併せて決定する（現時点でファサードの形を先取りで確定させると、P8の実際の要件と
食い違うリスクがある）。

`prompt-engine-application`が`prompt-engine-core`（`promptengine.engine..`）に依存しないことは
`ArchitectureTest`の既存ルール（`prompt-engine-application は prompt-engine-domain のみに
依存する`）で既に検証されており、新規ルールの追加は不要（P0時点で追加済み。
`prompt-engine-application`に実クラスが存在しないため現状は`allowEmptyShould(true)`で
実質未検証だが、P8で実クラスが追加された時点から実効化される）。

## 影響範囲

- `prompt-engine-domain`: `VariableSource`追加、`VariableDefinition`に`source`+不変条件、
  `PromptVersion`/`NewPromptVersion`/`PromptVersionMemento`/`CompiledPrompt`の
  `contextRequirement`→`contextRequirements`化、`Prompt`の3呼出箇所、
  `SecretManagerAdapter`インターフェース新設（`domain.variable`）、
  `VariableResolver`/`VariableResolverChain`（`domain.variable`）、
  `ContextResolver`（`domain.context`）、`PromptRequest`（`domain.shared`）の新設・移設
- `prompt-engine-core`: `CompositionServiceImpl`の`contextRequirements`追随、
  P4 Resolver一式（別コミット）、`VariableResolverChain`→`VariableResolverChainImpl`への
  改称と`domain.variable.VariableResolverChain`実装化、各Resolverのimport更新
- `prompt-engine-infrastructure`: Flyway V4、`EventStorePromptRepository` /
  `JdbcTemplateRepository` / `JdbcFragmentRepository` / `PromptSnapshotPayload`の
  `source`列・`context_requirements`配列対応、`SecretManagerAdapter`のM1環境変数実装
- `tests/integration`: 3つのRepository往復テストに`source`明示・`contextRequirements`化を反映
- 設計書§2.8・§4.4・§12に`source`を追記、`context_requirement`→`context_requirements`に修正

## 参照

- [PromptEngine_設計書.md §2.7 / §2.8 / §4.4 / §5.4 / §12 / §13.3 / §15.2](../PromptEngine_設計書.md)
- [ADR-0007: sensitive=trueの変数はリテラルのdefaultを持てない](0007-sensitive-variable-no-literal-default.md)
- [ADR-0009: CompositionService参照解決基盤](0009-composition-service-reference-resolution.md)
