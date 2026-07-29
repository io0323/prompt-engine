# ADR-0012: Validation Engine（P5）のドメイン表現・実行モデルを確定する

## ステータス

Accepted

## コンテキスト

P5（Validation Engine）実装にあたり、設計書§2.10（Validation仕様）・§15.7（DSL内validation宣言）・
§3.4（Interface定義）・§5.5（シーケンス）・§13.3（エラー形式）を横断しても、以下の6点が
未決／実装と食い違ったままでは着手できないことが分かった。

### 1. `ValidationRule.validate` の引数型が実在しない

§3.4疑似コードは `validate(ast: ExpandedAst, bindings: BindingSet): list<Finding>` と定義するが、
`ExpandedAst`という型はこのリポジトリに存在しない。実際にP3c（Composition）が生成する
「展開済みAST」は `CompiledPrompt.body: List<PromptAst>`（extends/import/include/macro展開済み、
ADR-0009）である。また§2.6ステージ6の入力は「AST + Bindings」であり、Variable/Context
両方の束縛（`BindingSet`と`ContextBindingSet`）が必要（未宣言Contextスコープ検出・
DependencyValidationの`dependencies`参照にはCompiledPrompt自体も要る）。

### 2. `validation:` DSL宣言（§15.7）を保持するドメイン型が存在しない

`PromptVersion`/`CompiledPrompt`は`variables`/`contextRequirements`は持つが、
`maxLength`/`maxTokens`/`policies`/`placeholders`に対応する型を持たない。

### 3. `ValidationRule.severity()`（単一値）と§13.3 Findingごとのseverityの粒度が矛盾する

§3.4は`ValidationRule`に`severity(): Severity`という「Rule単位で固定の1値」を持たせるが、
§13.3のエラー形式例は`details[].severity`という「Findingごとの値」を示す。さらに
「DSLの`validation.placeholders: strict|lenient`でseverityを切り替える」（§15.7・実装ガイド§6.6）
はPrompt（=CompiledPrompt）ごとに変わる値であり、Rule自体に固定できない。

### 4. Rule前提が崩れるケース（例: Compile-onlyでBindingが無い）の扱いが未定義

Compile-onlyモード（§2.6実行モード(c)、ステージ1〜3+6のみ）はステージ4〜5
（Variable/Context解決）を経ないため、SchemaValidation/ParameterValidationが検証すべき
「呼出パラメータ」自体が存在しない。

### 5. `LengthValidation`はRenderより前の段階で文字数/Token数をどう見積もるか未定義

Validation（ステージ6）はRendering（ステージ8）より前に実行されるため、実際の
`RenderedPrompt`は存在しない。一方§16拡張ポイント#13`TokenizerPlugin`は§3.4に
インターフェース定義が無く、実装（`plugins/tokenizer-approx`）もP6（実装ガイド§6.7）まで
存在しない。

### 6. `ParameterValidation`の`constraints`解析フォーマットが未確定

`VariableDefinition.constraints: List<String>`（P1）は`"maxLength:64"`という1例のみが
テストに存在し、`pattern`/`min`/`max`/`enum`の具体的な文字列表現は未確定。

これらはユーザーとの事前協議（本セッション、AskUserQuestion）で4点（1・2・4・5、下記決定の
1相当・2・4・5）を確認済み。3・6は協議で明示的には問われなかったが、設計書の記述だけでは
実装できない実質的な矛盾/欠落であり、[[feedback-adr-before-domain-changes]]の方針に従い
本ADRで併せて決定する。

## 決定

### 1. Interfaceはdomain、実装はcore（既存パターンを踏襲）

`CompositionService`/`VariableResolverChain`/`ContextResolver`と同じ形。

- `promptengine.domain.validation`: `Severity`（enum: `ERROR`/`WARNING`/`INFO`）、`Finding`
  （`ruleId: String, path: String, severity: Severity, message: String`。`path`は§13.3の
  `"$.parameters.productName"`のようなJSONPath風の自由記述文字列）、`ValidationReport`
  （`findings: List<Finding>` + `val hasErrors: Boolean get() = findings.any { it.severity == Severity.ERROR }`）、
  `ValidationRule`（Interface）、`ValidationEngine`（Interface）、`ValidationSettings`
  （後述）、`PlaceholderMode`（enum: `STRICT`/`LENIENT`）
- `promptengine.domain.tokenizer`: `TokenizerPlugin`（Interface、後述）
- `promptengine.engine.validation`（`prompt-engine-core`）: `ValidationEngineImpl`と標準5 Rule
  （`SchemaValidationRule`/`PlaceholderValidationRule`/`ParameterValidationRule`/
  `LengthValidationRule`/`DependencyValidationRule`）、M1暫定`TokenizerPlugin`実装
- `PolicyValidationRule`は`plugins/validator-policy`
  （`promptengine.plugin.validator.policy`、ADR-0003準拠）

`ValidationRule`のシグネチャは§3.4疑似コードの`ExpandedAst`を`CompiledPrompt`に、
`bindings: BindingSet`を`variableBindings: BindingSet, contextBindings: ContextBindingSet`に
読み替える（実在しない型を実在の型へ対応させる、ADR-0011における`VariableResolver`の
実装配置整理と同種の「疑似コードを実際の型へ対応させる」対応）:

```kotlin
interface ValidationRule {
    fun id(): String
    fun severity(): Severity
    fun validate(
        compiled: CompiledPrompt,
        variableBindings: BindingSet,
        contextBindings: ContextBindingSet,
    ): List<Finding>
}

interface ValidationEngine {
    fun validate(
        compiled: CompiledPrompt,
        variableBindings: BindingSet,
        contextBindings: ContextBindingSet,
    ): ValidationReport
}
```

`ValidationEngine.validate`は例外を投げない。ERROR Findingの有無で
「合格/不合格（VALIDATION_FAILED）」を判定するのは呼出元（P8 Pipeline Orchestrator）の
責務であり、Validation自体は「問題を発見して報告する」ことが本来の仕事であるため
（§5.5シーケンス図の`alt ERROR含む`分岐もEngine自身ではなくPO側の分岐として描かれている）。

### 2. `ValidationSettings`を`PromptVersion`/`CompiledPrompt`に新設する（`contextRequirements`と同型）

```kotlin
enum class PlaceholderMode { STRICT, LENIENT }

data class ValidationSettings(
    val maxLength: Int? = null,
    val maxTokens: Int? = null,
    val policies: List<String> = emptyList(),
    val placeholders: PlaceholderMode = PlaceholderMode.LENIENT,
)
```

`PromptVersion`/`NewPromptVersion`/`PromptVersionMemento`に`validation: ValidationSettings`
（既定値`ValidationSettings()`）を追加し、`CompiledPrompt`にもそのまま引き継ぐ
（`contextRequirements`と同様、Template/Fragmentの`validation`とはマージしない。
Prompt自身の宣言のみが有効。TemplateVersion/FragmentVersionはそもそも`validation`を
持たない）。全フィールド省略時の既定は「無制限・lenient」とする。既存Promptの挙動を
変えない後方互換な既定値を優先し、新規Promptが`validation:`を書かない限り
PlaceholderValidationがいきなりERRORを出す変化を避けるため（`STRICT`をデフォルトに
しない）。

DSLの`validation:`フロントマターは`prompt-engine-core`の`ValidationFieldMapper`
（`ImportsFieldMapper`/`MacrosFieldMapper`と同型のパターン）が`ValidationSettings`へ
変換する。この変換は`PromptVersion`構築（Aggregate操作）とは別軸の、DSL取り込み時の
関心事であり、他のFieldMapperと同じ扱いとする。

永続化: `prompt_versions.validation`（JSON）列をFlyway V5で追加する（`context_requirements`
と同じ形。P2〜P4時点でこのテーブルは本番データを持たないため列追加のみ、データ移行不要）。

### 3. Findingが実際のseverityを持ち、`ValidationRule.severity()`は既定値として扱う

`ValidationRule.severity()`は「そのRuleが通常报告する既定severity」を表すが、実際に
`ValidationReport`へ積まれる各`Finding`は自分自身の`severity`フィールドを持つ
（§13.3の形式どおり）。`PlaceholderValidationRule`は`severity() = Severity.ERROR`
（strict相当を既定値として宣言）を返しつつ、実際に生成する`Finding`のseverityは
呼出のたびに`compiled.validation.placeholders`（`STRICT`→ERROR、`LENIENT`→WARNING）
から都度計算する。他の標準Ruleは`severity()`の値をそのままFindingに使う（呼出ごとに
変えない）。

### 4. 各Ruleは前提を自己防御的に判定し、満たさなければ例外を投げず「該当なし」を返す

Engine側（`ValidationEngineImpl`）はモードやbindingsの状態を判定する分岐を一切持たず、
常に登録済み全Ruleを一様に呼ぶ。各Rule自身が「自分の検証に必要な入力が無い/空」なら
例外を投げずに空リスト（Finding無し）を返す。例:

- `SchemaValidationRule`/`ParameterValidationRule`は`variableBindings`に実際に含まれる
  キーだけを検証する。Compile-onlyで`variableBindings`が空なら、検証対象0件で
  自然に空リストを返す（「呼出パラメータが無い」＝検証すべきことが無い、であり
  スキップ通知のFindingは残さない）。
- `PlaceholderValidationRule`はASTの宣言/参照突合せのみを行い、実際の値は不要なため
  Compile-onlyでも通常どおり機能する。
- 上記のいずれにも該当しない、想定外の不整合（例: `CompiledPrompt`自体がプログラミング
  エラーで不正な状態）はRule内で防御せず、通常の例外（NPE等）をそのまま
  `ValidationEngineImpl`から呼出元へ伝播させる（握りつぶさない）。

### 5. `LengthValidationRule`はASTベストエフォート推定でテキスト化し、`TokenizerPlugin`に渡す

`TokenizerPlugin`を新設する（`domain.tokenizer`、他の拡張ポイントInterfaceと同じく
domain配置。§16拡張ポイント#13に対応するが§3.4に定義が無かったため、他のEngine系
Interfaceと同型で本ADRにて新設する）:

```kotlin
interface TokenizerPlugin {
    fun estimate(text: String): TokenCount
}
```

`LengthValidationRule`は`CompiledPrompt.body`を再帰的に走査し、以下の規則でベストエフォートの
テキストへ変換してから`maxLength`（文字数）判定・`TokenizerPlugin.estimate`（Token数判定）を行う:

- `TextNode`: そのままのテキストを連結する。
- `ExprNode`: `PropertyRef`の先頭セグメントが変数名なら`variableBindings`、
  `"context"`なら`contextBindings`（`"<scope>.<path>"`キー）から値を引き、
  見つかればその`toString()`を連結する（`SensitiveValue`は`toString()`で自動的に
  `"***"`にマスクされるため、Secret変数の実値が長さ推定に混入することはない）。
  見つからなければ0文字（空文字列）として扱う。`truncate(n)`フィルタが付いていれば
  結果をその長さで切り詰める（他のフィルタ、例: `upper`は長さに影響しないため無視する）。
- `IfNode`: `thenBranch`/`elseBranch`それぞれを再帰的にテキスト化し、
  文字数が長い方を採用する（予算超過の見逃しより過大見積りを許容する安全側）。
- `EachNode`: `iterable`が`variableBindings`/`contextBindings`上で実際に`List<*>`として
  解決できればその要素数だけ`body`のテキスト化を繰り返し連結する。解決できなければ
  （Compile-only等）1回分として扱う。
- `BlockNode`: `role`を問わず`body`をそのまま連結する（文字数/Token数の合計に
  role区分は影響しない）。
- `IncludeNode`/`MacroCallNode`: Composition（P3c）で必ず展開済みのはずであり
  Validation段階のASTには本来出現しない。防御的に空文字列を返す（クラッシュしない）。

M1の`TokenizerPlugin`実装（`engine.validation`内、`NaiveTokenizerPlugin`）は
「文字数を4で割って切り上げ」という暫定の近似実装とし、KDocに明記する。
P6で`plugins/tokenizer-approx`（正式なPlugin実装）に差し替わる想定（実装ガイド§6.7）。

### 6. `constraints`文字列フォーマットを`<key>:<value>`に統一する

既存の`"maxLength:64"`（P1）を一般化し、`ParameterValidationRule`は以下のキーを解釈する:

- `pattern:<regex>`: 文字列値が正規表現にマッチしなければ違反（STRING型が対象）
- `min:<number>` / `max:<number>`: 数値値がその範囲外なら違反（NUMBER型が対象）
- `enum:<comma区切りの値>`: 値がリストに含まれなければ違反
- `maxLength:<number>`: 文字列長がその値を超えれば違反（既存）

未知のキーは無視する（前方互換。将来キーが増えてもParameterValidationRuleが
壊れない）。この対応表はKDocに明記し、設計書§15.2に「`constraints`の具体的な
文字列表現」として追記する。

### 7. `DependencyValidationRule`はリポジトリを引かず、既に確定済みのStatusのみ報告する

`CompiledPrompt.dependencies[].status`をそのまま読む。STANDARDモードでは
CompositionService（P3c、ADR-0009）が既にDraft参照を`DraftReferenceNotAllowedException`で
拒否済みのため、Validation段階に到達した時点で全依存は必ずPublished — このRuleが
実際に何かを報告するのはCompile-onlyモード（Draft参照が意図的に許可される）のみである。
したがって`severity() = Severity.WARNING`固定とする（「拒否」はP3cが既に済ませており、
ここでのFindingは「Compile-onlyでDraftを含んでいる」という注意喚起であってブロッカーではない）。

## 影響範囲

- `prompt-engine-domain`: `domain.validation`（`Severity`/`Finding`/`ValidationReport`/
  `ValidationRule`/`ValidationEngine`/`ValidationSettings`/`PlaceholderMode`）、
  `domain.tokenizer`（`TokenizerPlugin`）新設。`PromptVersion`/`NewPromptVersion`/
  `PromptVersionMemento`/`CompiledPrompt`に`validation`フィールド追加
- `prompt-engine-core`: `engine.validation`（`ValidationEngineImpl`、標準5 Rule、
  `NaiveTokenizerPlugin`）、`engine.compiler.ValidationFieldMapper`新設、
  `CompositionServiceImpl`が`validation`を引き継ぐよう追随
- `plugins/validator-policy`（新設Gradleサブプロジェクト）: `PolicyValidationRule`
  （`promptengine.plugin.validator.policy`）
- `prompt-engine-bootstrap`: `build.gradle.kts`に`testImplementation(project(":plugins:validator-policy"))`
  追加、`ArchitectureTest`の規約6テスト（`Plugin実装は...`）から`allowEmptyShould(true)`除去
- `prompt-engine-infrastructure`: Flyway V5（`prompt_versions.validation`列追加）、
  `EventStorePromptRepository`/`PromptSnapshotPayload`が`validation`を読み書き
- `tests/integration`: `EventStorePromptRepositoryIntegrationTest`に`validation`の
  往復を反映
- 設計書§2.10に本ADRの参照注記、§15.2に`constraints`文字列表現の追記、
  §15.7に`ValidationSettings`との対応注記

## 参照

- [PromptEngine_設計書.md §2.6 / §2.7 / §2.10 / §3.4 / §5.5 / §13.3 / §15.2 / §15.7 / §16](../PromptEngine_設計書.md)
- [ADR-0003: Plugin実装のパッケージ命名規則](0003-plugin-package-naming.md)
- [ADR-0009: CompositionService参照解決基盤](0009-composition-service-reference-resolution.md)
- [ADR-0011: VariableSource / ContextRequirement複数形化](0011-variable-source-and-context-requirement-list.md)
