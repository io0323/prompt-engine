# ADR-0013: Optimization Engine / Render Engine（P6）のドメイン表現・決定性担保方針を確定する

## ステータス

Accepted

## コンテキスト

P6（Optimization + Render）実装にあたり、設計書§2.9（Rendering仕様）・§2.11（Optimization仕様）・
§4.4（VO一覧）・§5.6〜5.7（シーケンス）・§3.4（Interface疑似コード）・§16（拡張ポイント）を
横断しても、以下が未決／実装と食い違ったままでは着手できないことが分かった。事前協議
（本セッション、AskUserQuestion）で確認済み。[[feedback-adr-before-domain-changes]]の方針に従い、
実装前に本ADRを起票する。

### 1. `renderHash`の入力定義・正規化規則が未定義

実装ガイド§6.7は「`renderHash = SHA-256(正規化messages + engineId + engineVersion)`」とだけ述べ、
「正規化」の具体的な規則（改行コード・末尾空白・Unicode正規化形・数値の文字列化）を定義していない。

### 2. `BlockRole`（AST、P3で確定済み）と`RenderedPrompt.messages[].role`（本設計書§2.9）の集合が食い違う

`promptengine.domain.template.ast.BlockRole`（P3、設計書§15.1「roleはsystem/user/assistantに限定」
準拠）は`SYSTEM/USER/ASSISTANT`の3値のみを持つ。一方§2.9は「roleは
system/user/assistant/toolの抽象role」と`RenderedPrompt.messages[].role`に`tool`を含めている。
これは矛盾ではなく、`BlockRole`＝著者がDSLで書けるブロックのrole（§15.1の制約どおり3値）と、
`RenderedPrompt`の出力role（将来の多段実行・Tool結果replay等を見込んだ4値）という
別レイヤーの型であるという整理で解決する。

### 3. `RenderedPrompt`のフィールド集合が資料間で食い違う

§2.9本文は`{ messages, outputFormat, modelHints, tokenEstimate, renderHash }`と`modelHints`を含むが、
§4.4 VO一覧は`messages[] / outputFormat / tokenEstimate / renderHash`と`modelHints`を含まない。
実装ガイド§6.7のP6スコープ定義も同様に`modelHints`を含まない。

### 4. `ModelProfile.capabilities`の型が未定義

§2.11・§4.4とも「`capabilities`」とだけ書かれ、値の型・列挙が定義されていない。実際に参照される
条件は§2.11 Expansion行の「ModelProfileが指示追従弱と定義する場合」の1点のみ。

### 5. `OutputFormat`型が本来P7（実装ガイド§6.8、OutputFormatter）のスコープだが、P6の`RenderedPrompt.outputFormat`が先に必要とする

§3.4の`OutputFormatter.format(): OutputFormat`はP7で実装するInterfaceだが、P6の
`RenderedPrompt`（§4.4）は`outputFormat`フィールドを持つ必要があり、型定義自体はP6時点で
必要になる。

### 6. Compressionの適用範囲（要約 vs 切詰）

§2.11は「会話履歴・Contextの要約/切詰」と書くが、要約（LLMによる意味圧縮）はAPAP連携が
必要でM1のスコープ外（ユーザー確認済み）。

### 7. `BlockNode`からmessagesへの変換規則が未定義

同一role重複時の扱い、blockが0件の場合の扱い、role出現順とmessages順序の関係が
設計書に明記されていない。

## 決定

### 1. `renderHash`の入力と正規化

```
renderHash = SHA-256(normalize(messages) + " " + engineId + " " + engineVersion)
```

を16進文字列として`RenderedPrompt.renderHash`に格納する。

- **messages**: `BlockNode`から変換された`List<{role: MessageRole, content: String}>`
  （順序は決定3のBlockNode変換規則により、AST走査順＝決定的）。
- **改行コード**: `\r\n`・`\r`はハッシュ計算前に`\n`へ正規化する。
- **末尾空白**: 各行末の空白・タブのみ除去する（行内の空白・メッセージ全体のtrimは行わない。
  意味のある空白を破壊しないため、行末の「編集ツール由来の空白」のみを対象とする）。
- **Unicode正規化形**: NFC（正準結合）に正規化する。フィルタ適用後・リテラル連結後の
  最終テキストに対して適用する（属性値の混入経路によらず一意に正規化するため）。
- **role**: enum名の文字列（`"SYSTEM"`/`"USER"`/`"ASSISTANT"`/`"TOOL"`）としてハッシュに含める。
- **engineId / engineVersion**: `TemplateEngine.id()`（例: `"pe-tmpl/1"`）と`RenderEngine`が持つ
  バージョン定数を、`messages`とは別セグメントとしてハッシュ入力に連結する
  （区切りに`" "`を用い、`content`内の文字列が偶然`engineId`と結合して衝突することを防ぐ）。
- **sensitive値**: `SensitiveValue.expose()`（生値）をハッシュ計算に使う。`toString()`
  （`"***"`）は使わない。マスクはログ・キャッシュキー・Audit出力の境界でのみ行い、
  `RenderEngine`自体は生値を保持したまま返す（実行に必要なため、実装ガイド§6.7の指示どおり）。
- **outputFormat**: ハッシュに含める（`enum`名の文字列）。`OutputFormatter.instruction(schema)`が
  生成する指示文はすでに`messages`の内容として連結されるため、`outputSchemaRef`自体を
  別セグメントとして追加はしない（重複した入力を避ける）。

### 2. 非決定性要因の構造的排除

| 要因 | 排除方針 |
|---|---|
| Map/Set反復順序 | `RenderEngine`/`DefaultTemplateEngine`は`BindingSet.values`/`ContextBindingSet.values`全体を`Map`として反復するコードを一切書かない。値の参照は`PropertyRef`パスによるピンポイントのキー参照のみ。反復が必要なのは`EachNode.iterable`が`List`型に解決された場合のみで、`List`は元々順序を持つため問題にならない。 |
| ロケール依存の文字列処理 | `upper`/`lower`フィルタは`String.uppercase(Locale.ROOT)`/`lowercase(Locale.ROOT)`（引数無しの`uppercase()`/`lowercase()`は使用禁止）で実装する。`engine.render`・`engine.optimization`パッケージ内で引数無し`uppercase()`/`lowercase()`・`toUpperCase()`/`toLowerCase()`の呼び出しを禁止するArchUnitルールを追加する。 |
| 浮動小数の文字列化 | `NumberLiteral.value: Double`は次の規則で文字列化する: 整数値（`value == value.toLong().toDouble()`）なら小数点無しの整数表記（`toLong().toString()`）、それ以外は`Double.toString()`（Java/Kotlinの`Double.toString()`はロケール非依存で常に`.`区切りのため、追加のフォーマッタは導入しない）。 |
| 現在時刻・乱数 | Context経由の値以外からの混入を、`engine.render`・`engine.optimization`パッケージ内で`java.time.Instant.now()`・`System.currentTimeMillis()`・`kotlin.random.Random`（および`java.util.Random`）の呼び出しを禁止するArchUnitルールで構造的に防ぐ（`ExtendsRefApi`と同種の「該当パッケージからの特定APIアクセス禁止」パターン）。 |

### 3. Compression: M1は切詰のみ、要約は将来フェーズ

Compressionは§2.11の優先順位（conversation古い順→memory）に従った**切詰のみ**を実装する。
LLMによる要約はAPAP連携が必要なためM1スコープ外とする（ユーザー確認済み）。

`OptimizationReport`は切り詰めたスコープごとに次を記録する:

```kotlin
data class TruncationNote(
    val scope: String,               // 例: "conversation", "memory"
    val originalTokenEstimate: TokenCount,
    val truncatedTokenEstimate: TokenCount,
    val summary: String,             // 例: "dropped 3 oldest of 8 entries"（切り詰めた中身自体は含めない）
)
```

`summary`には切り詰めた実際のテキスト内容を含めない（Auditへそのまま出力されるため、
機密混入を避ける。件数・トークン数などのメタ情報のみ）。

### 4. `BlockNode`からmessagesへの変換規則

- 同一`role`のBlockが複数存在する場合: **出現順のまま個別のmessageとして保持する**
  （役割ごとに1つへマージ・結合はしない。順序と発話単位をそのまま保つ）。
  同一roleの連続するBlockを1つのmessageへ結合したい場合は、著者がDSL側で1つのBlockに
  まとめて書くべきであり、Render Engineが暗黙に結合すると著者の意図しない文脈結合が
  起きうるため行わない。
- Blockが1つも無い場合: 本文全体（`CompiledPrompt.body`の全ノード）を単一の
  `USER`役割messageとして扱う（空の`messages[]`はExecution不能であり、`SYSTEM`を
  勝手に補うのも著者の意図と異なりうるため、対話の入力として扱うのが最も安全なデフォルト）。
- messagesの順序: **AST上のBlock出現順**とし、role単位でのグルーピング・並べ替えは行わない
  （例: `system, user, system, assistant`の出現順ならその順のまま。role別にまとめない）。

### 5. `MessageRole`を新設し、`BlockRole`とは別の型とする

```kotlin
// domain.render
enum class MessageRole { SYSTEM, USER, ASSISTANT, TOOL }
```

`BlockNode.role: BlockRole`（3値、DSL著者が書ける値）から`MessageRole`（4値）へは
`SYSTEM→SYSTEM, USER→USER, ASSISTANT→ASSISTANT`の1:1対応で変換する。`TOOL`はM1の
`RenderEngine`からは一切生成されない（対応するBlockRole値が存在しないため構造的に
発生しえない）。将来のマルチターン実行（Tool結果のreplay等）向けの予約値として型に
含めておく。

### 6. `RenderedPrompt`は`modelHints`を含めない（M1）

§4.4 VO一覧および実装ガイド§6.7のP6スコープ定義（`messages[{role, content}] / outputFormat /
tokenEstimate / renderHash`）に合わせ、M1の`RenderedPrompt`から`modelHints`を除外する。
§2.9本文の記述と齟齬があるため、本ADR確定を受けて design書§2.9 を修正する
（`modelHints`はAPAP/Provider方言吸収に関わる情報であり、APAP連携が具体化するP7以降で
必要になった時点で追加する）。

```kotlin
// domain.render
data class RenderedPrompt(
    val messages: List<RenderedMessage>,
    val outputFormat: OutputFormat,
    val tokenEstimate: TokenCount,
    val renderHash: String,
)

data class RenderedMessage(val role: MessageRole, val content: String)
```

### 7. `ModelProfile`と`ModelCapability`

```kotlin
// domain.optimization
enum class ModelCapability { WEAK_INSTRUCTION_FOLLOWING }

data class ModelProfile(
    val maxContextTokens: TokenCount,
    val tokenizerId: String,
    val costPerToken: Cost,
    val capabilities: Set<ModelCapability> = emptySet(),
)
```

`capabilities`は現時点で参照される唯一の条件（Expansionルールの適用条件）に対応する
`WEAK_INSTRUCTION_FOLLOWING`のみを定義する。将来別の条件が必要になれば値を追加するのみで
既存コードへの破壊的変更を伴わない（列挙値の追加は非破壊）。`tokenizerId`は
プロバイダ名・モデル名を直接指さない不透明な識別子とし、実際の`TokenizerPlugin`実装への
解決は呼出側（将来のDI結線）の責務とする（CLAUDE.md「特定のAIプロバイダ名・モデル名を
コードに直接書かない」）。

### 8. `OutputFormat`を`domain.render`にP6で新設する（P7 `OutputFormatter`が再利用）

```kotlin
enum class OutputFormat { JSON, XML, MARKDOWN, TEXT }
```

§3.4の`OutputFormatter.format(): OutputFormat`（P7実装対象）が参照する型としてそのまま
再利用する想定。P6時点では`RenderedPrompt.outputFormat`が要求する型としてのみ必要であり、
`OutputFormatter`インターフェース自体（`instruction`/`parse`）はP7で追加する。

### 9. `OptimizationEngine`/`OptimizationRule`・`TokenBudgetExceededException`

§3.4疑似コードの`ExpandedAst`を、Validation（ADR-0012）と同様に`CompiledPrompt`へ
読み替える。

**当初案（`compiled`と`profile`のみ）からの訂正**: 実装検討の過程で、この2引数だけでは
`Compression`（会話履歴・Contextの切詰）と`ContextOptimization`（参照されないContextスコープの
除去）が実行不可能であることが判明した。会話履歴・Contextスコープのデータは
`CompiledPrompt.body`（AST）には存在せず、`ContextBindingSet.values`（P4、
`"<scope>.<path>"`キーのマージ済み値）に存在するため、これらのRuleは`CompiledPrompt`ではなく
`ContextBindingSet`を書き換える必要がある。§2.6ステージ表の「7 | Optimization | 束縛済AST +
ModelProfile」という表現も、§3.4 `PipelineContext`が`ast`/`variableBindings`/`contextBindings`を
別フィールドとして持つ構造と整合させると「ASTと（Variable/Context）Bindingsの両方が
利用可能な状態」という意味であり、「AST自体に値を埋め込み済み」ではないと解釈するのが
妥当（ADR-0012のExpandedAst解釈と同種の「疑似コード上の省略表現を実際の型へ対応させる」
対応）。

`OptimizationRule`自体（`Compression`/`ContextOptimization`）が書き換えるのは`contextBindings`
のみ（`variableBindings`＝呼出パラメータはCompression/ContextOptimizationいずれの対象
（会話履歴・Context）にも含まれない）。一方`OptimizationEngine`自身が行うTokenEstimate算出
（Rule適用前後の見積り、budget超過判定）は、実際にRenderされる全文の近似が必要であり、
`variableBindings`由来のテキストも見積りに含める必要がある（`LengthValidationRule`が使う
`AstTextEstimator`と同じ理由、ADR-0012決定5）。したがって`variableBindings`は
`OptimizationEngine.optimize`の引数にのみ含め、個々の`OptimizationRule`へは渡さない
（Ruleが実際に書き換えない入力を渡さないことで、各Ruleの責務を最小化する）。

§2.11の「Compression: tokenEstimate > budget」という適用条件も、§5.6シーケンスの
`applicable(ast, profile)`という2引数シグネチャとは食い違う（`budget`も現在のtokenEstimateも
渡されない）。これも同様に疑似コードの省略と判断し、`applicable`に`estimatedTokens`と
`budget`を追加する。これらは`OptimizationEngine`が（`variableBindings`も使って）算出し、
各Ruleへ引き渡す値であり、Rule自身が独自に見積りを再計算する必要はない
（見積りロジックを各Rule実装に重複させない）。同じ理由で`optimize`にも`estimatedTokens`・
`budget`を渡す（`Compression`は「どこまで切り詰めれば`budget`以内に収まるか」を
自身の`optimize`呼出内で判断する必要があり、`applicable`だけが知っていても`optimize`側で
再度算出し直すのは重複になるため）。

```kotlin
// domain.optimization
interface OptimizationRule {
    fun id(): String
    fun applicable(
        compiled: CompiledPrompt,
        contextBindings: ContextBindingSet,
        profile: ModelProfile,
        estimatedTokens: TokenCount,
        budget: TokenCount,
    ): Boolean
    fun optimize(
        compiled: CompiledPrompt,
        contextBindings: ContextBindingSet,
        profile: ModelProfile,
        estimatedTokens: TokenCount,
        budget: TokenCount,
    ): RuleOptimizationResult
}

data class RuleOptimizationResult(
    val compiled: CompiledPrompt,
    val contextBindings: ContextBindingSet,
    val note: OptimizationNote,
    val truncations: List<TruncationNote> = emptyList(),
)

interface OptimizationEngine {
    fun optimize(
        compiled: CompiledPrompt,
        variableBindings: BindingSet,
        contextBindings: ContextBindingSet,
        profile: ModelProfile,
        budget: TokenCount,
    ): OptimizationOutcome   // throws TokenBudgetExceededException
}

data class OptimizationOutcome(
    val compiled: CompiledPrompt,
    val contextBindings: ContextBindingSet,
    val report: OptimizationReport,
)

data class OptimizationNote(val ruleId: String, val tokensSaved: TokenCount, val detail: String)

data class OptimizationReport(
    val appliedRules: List<OptimizationNote>,
    val truncations: List<TruncationNote> = emptyList(),
)

class TokenBudgetExceededException(val estimated: TokenCount, val budget: TokenCount) :
    RuntimeException("TOKEN_BUDGET_EXCEEDED: estimated $estimated exceeds budget $budget")
```

`OptimizationEngineImpl.optimize`のアルゴリズム: (1) `AstTextEstimator.estimate(compiled.body,
variableBindings, contextBindings)`を`TokenizerPlugin.estimate`へ渡し現在のTokenEstimateを
算出、(2) 登録順で各Ruleについて、現在のTokenEstimateを使い`applicable`がtrueなら
`optimize`を適用し`compiled`/`contextBindings`/`appliedRules`/`truncations`を更新、
その都度TokenEstimateを再計算（あるRuleの適用が後続Ruleの`applicable`判定に影響しうるため、
`estimatedTokens`は固定値ではなく毎回最新の値を使う）、(3) 全Rule適用後もなお
TokenEstimateが`budget`を超える場合のみ`TokenBudgetExceededException`を投げる（§5.6
シーケンスの`alt estimate > budget`分岐）。

ValidationEngineと異なり、ここは「例外を投げない」設計にしない。理由は§5.6が明示的に
例外的分岐として描いており、§2.6ステージ表も「警告のみで継続可（予算超過は
TOKEN_BUDGET_EXCEEDED）」と、予算超過時点をパイプライン続行不可の分岐点として扱っている
ため（Validationの「見つけて報告するだけ」とは役割が違う）。Rule適用順序は登録順のみで、
各Ruleは他のRuleの内部状態に依存しない（Chain of Responsibilityではなく、疑似コードどおりの
単純なloop適用、ADR-0012のValidationRuleとは異なりseverity概念を持たない）。TokenEstimate
算出には`AstTextEstimator`（`engine.validation`、ADR-0012決定5）と`TokenizerPlugin`を
そのまま再利用する（同一モジュール内のパッケージ間参照であり、モジュール境界を越えない）。

`TokenOptimization`/`Compression`/`Expansion`/`ContextOptimization`を個別クラスとして実装する。
`TokenOptimization`の`applicable`は常にtrue（無効化したい場合はコンストラクタの
`enabled: Boolean`フラグで制御し、Rule自体を登録から外すのではなく個別に無効化できるように
する）。`Compression`の`applicable`は`estimatedTokens.value > budget.value`。`Expansion`の
`applicable`は`profile.capabilities.contains(ModelCapability.WEAK_INSTRUCTION_FOLLOWING)`。
`ContextOptimization`の`applicable`は常にtrue（§2.11「常時」）で、`PropertyRefCollector`
（`engine.validation`）が`compiled.body`から収集した`context.*`参照パスのスコープ集合に
含まれない`contextBindings.values`のキーを除去する。

**`Expansion`はM1では「詳細指示の追加」のみを実装し、「Few-shot例の追加」は対象外とする。**
§2.11は`Expansion`を「Few-shot例・詳細指示の追加」と定義するが、Few-shot例をASTへ注入するには
テンプレート単位の「例データ」の供給元が必要であり、現行のドメインモデル（`CompiledPrompt`/
`PromptVersion`/`TemplateVersion`/`FragmentVersion`）にはそのような供給元
（`examples:`DSL宣言、専用Fragment参照種別等）が一切定義されていない。存在しない型・宣言を
推測で新設することはCLAUDE.md「設計書にない...を勝手に追加しない」に反するため、供給元の設計は
別途ユーザーと合意の上でADRを起こす（追跡: [Issue #29](https://github.com/io0323/prompt-engine/issues/29)）。
M1の`ExpansionRule`は`profile.capabilities`が`WEAK_INSTRUCTION_FOLLOWING`を含む場合に
固定の指示文（詳細指示）をSYSTEMブロックへ追記するのみ。

### 10. `RenderEngine`/`TemplateEngine`

```kotlin
// domain.render
interface TemplateEngine {
    fun id(): String
    fun expand(
        body: List<PromptAst>,
        variableBindings: BindingSet,
        contextBindings: ContextBindingSet,
    ): List<RenderedMessage>
}

interface RenderEngine {
    fun render(
        compiled: CompiledPrompt,
        variableBindings: BindingSet,
        contextBindings: ContextBindingSet,
        outputFormat: OutputFormat,
    ): RenderedPrompt
}
```

`DefaultTemplateEngine`（`id() = "pe-tmpl/1"`）が式評価（`PropertyRef`解決・`upper`/`lower`/
`trim`/`truncate`/`default`フィルタ適用、決定2の規則に従う）・`IfNode`/`EachNode`展開・
`BlockNode`→`RenderedMessage`変換（決定4の規則）を行う。`EachNode`は`iterable`が実際に
`List<*>`へ解決できた場合のみ要素ごとに`itemName`を束縛して繰り返す。解決できない場合
（Compile-onlyや型不一致）は1回だけ、`itemName`を解決値そのもの（`null`なら束縛なし）に
束縛して実行する（`AstTextEstimator`の「1回分として扱う」という既存の安全側の扱いと
整合させる）。`RenderEngine`実装は`TemplateEngine`経由でのみASTを展開し（差替可能性の
担保、実装ガイド§6.7の指示どおり）、`OutputFormatter`は本ADRの対象外（P7）のため
`instruction()`は呼ばず、`outputFormat`は素通しでフィールドに設定するのみとする
（実際の指示文注入はP7以降でRenderEngineに組み込む）。

## 影響範囲

- `prompt-engine-domain`: `domain.render`（`MessageRole`/`RenderedMessage`/`RenderedPrompt`/
  `OutputFormat`/`TemplateEngine`/`RenderEngine`新設）、`domain.optimization`
  （`ModelCapability`/`ModelProfile`/`OptimizationNote`/`OptimizationReport`/`TruncationNote`/
  `RuleOptimizationResult`/`OptimizationOutcome`/`OptimizationRule`/`OptimizationEngine`/
  `TokenBudgetExceededException`新設）
- `prompt-engine-core`: `engine.render`（`DefaultTemplateEngine`、`RenderEngineImpl`）、
  `engine.optimization`（`OptimizationEngineImpl`、`TokenOptimizationRule`/
  `CompressionRule`/`ExpansionRule`/`ContextOptimizationRule`）新設。
  `engine.validation.NaiveTokenizerPlugin`を撤去し、`LengthValidationRuleTest`は
  テスト内ローカルの`TokenizerPlugin`スタブに置き換える（`LengthValidationRule`自体は
  Interface依存のため変更不要）
- `plugins/tokenizer-approx`（新設Gradleサブプロジェクト）: `ApproxTokenizerPlugin`
  （`promptengine.plugin.tokenizer.approx`、ADR-0003準拠）
- `prompt-engine-bootstrap`: `build.gradle.kts`に
  `testImplementation(project(":plugins:tokenizer-approx"))`追加、`ArchitectureTest`に
  非決定性要因排除のための新規ArchUnitルール（Locale無し大小文字変換禁止、
  `engine.render`/`engine.optimization`での時刻・乱数API禁止）を追加
- 設計書§2.9（`modelHints`除外の注記、`renderHash`正規化規則への参照）、§2.11
  （`ModelProfile.capabilities`の型への参照）、§4.4（`RenderedPrompt`/`ModelProfile`定義への
  ADR参照）に本ADRの参照注記を追記

## 参照

- [PromptEngine_設計書.md §2.6 / §2.9 / §2.11 / §3.4 / §4.4 / §5.6 / §5.7 / §16](../PromptEngine_設計書.md)
- [ADR-0003: Plugin実装のパッケージ命名規則](0003-plugin-package-naming.md)
- [ADR-0011: VariableSource / ContextRequirement複数形化](0011-variable-source-and-context-requirement-list.md)
- [ADR-0012: Validation Engine（P5）のドメイン表現・実行モデル](0012-validation-engine.md)
