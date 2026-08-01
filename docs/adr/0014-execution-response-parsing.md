# ADR-0014: Execution Adapter / Output Formatter（P7）のドメイン表現・リトライ方針を確定する

## ステータス

Accepted

## コンテキスト

P7（Execution + Response Parsing）実装にあたり、設計書§2.6ステージ9〜10・§3.4（Interface疑似コード）・
§5.8〜5.9（シーケンス）・§13.3（エラー形式）・§15.1（`output:`宣言）・§16（拡張ポイント）を横断しても、
以下が未決／実装と食い違ったままでは着手できないことが分かった。事前協議（本セッション、
自由記述形式でのやり取り）で確認済み。[[feedback-adr-before-domain-changes]]の方針に従い、
実装前に本ADRを起票する。

### 1. `ExecutionRequest`はdomain型として新設する必要が無い

§3.4疑似コードは `ExecutionAdapter.execute(p: RenderedPrompt, policy: ExecutionPolicy): RawResponse`
であり、引数は`RenderedPrompt`そのものである。`ExecutionRequest`が登場するのは§5.8シーケンスの
`AA -> APAP : ExecutionRequest(messages, modelHints)`のみで、これは`ApapExecutionAdapter`
（M2、`prompt-engine-infrastructure`）がAPAP呼出直前に内部変換する際の形であり、
`ExecutionAdapter`インターフェース自体の引数ではない。

### 2. `ExecutionPolicy` / `RawResponse` / `ParsedOutput` / `OutputSchema`のフィールド構成が未定義

設計書のどの節にもこれらの型のフィールド一覧は定義されていない（§3.4は型名の言及のみ）。
§13.2 Response例は`usage {inputTokens, outputTokens, cost}` `latencyMs`を示すが、これはAPI
Responseの形であり、ドメイン型`RawResponse`の形とは限らない。

### 3. リトライしてよいエラー種別が未定義

実装ガイド§6.8は「リトライは指数バックオフ。冪等でない副作用がないことをKDocに明記」とだけ求め、
具体的にどのエラーがリトライ対象かは定義していない。特にタイムアウトは、
「接続確立前のタイムアウト（未送信と断定できる）」と「応答待機中のタイムアウト（先方で実行済み・
課金済みの可能性を否定できない）」を区別せずに一括りにすると、後者を安全側に倒せない。

### 4. `OutputSchema`はDSLの`output.schemaRef`から解決されない

§15.1は`output: {format, schemaRef}`をfront matterに定義するが、`CompiledPrompt`
（`promptengine.domain.composition.CompiledPrompt`、P3c/ADR-0009）は`output`に対応する
フィールドを一切持たない。`RenderedPrompt.outputFormat`（P6）も同様に、DSLからの自動導出ではなく
呼出側が明示的に渡す値として実装されている（`RenderEngineImpl`のKDoc参照）。`schemaRef`が指す
実体（`schemas/faq-answer-v1`）を解決する`SchemaRepository`相当のInterfaceも§3.4に存在しない。

### 5. `RenderEngine`から`OutputFormatter.instruction()`への経路が未接続

§5.7シーケンスは`RE -> OF: instruction(outputSchema)`を経由して`messages[]`へフォーマット指示文を
注入した後に`renderHash`を算出する、と定めている。`RenderEngineImpl`（P6、ADR-0013決定10）は
「P7スコープのため素通しするのみ」と明記した未接続状態で実装されており、P7で接続する必要がある。
接続時、`instruction()`の返り値をどのメッセージに・どう挿入するかが未定義。

### 6. parseRepairの再実行が、既存の`RenderedPrompt`の型制約とどう整合するか未定義

§5.9シーケンスは「修復プロンプトで再実行」と書くが、`ExecutionAdapter.execute()`の引数は
`RenderedPrompt`固定であり、修復ラウンド用に新しい`RenderedPrompt`をどう構築するかが未定義。
`RenderedPrompt`は`renderHash`が非空であることのみを要求する型（値の正しさ自体は型で検証しない）
だが、`RenderEngineImpl`が持つハッシュ計算ロジックはprivateであり、再利用経路が無い。

### 7. リトライ責務がAPAPとPEでどちらの関心事か未確定

M1時点では実APAP接続が無く（M2）、PE自身が`ExecutionPolicy`に基づきリトライするしかない。
しかし設計書§2.1・実装ガイド§1.1が定めるAPAPの責務（プロバイダ/モデル抽象化、ルーティング）を
踏まえると、RATE_LIMITED / SERVER_ERRORのリトライは本来APAP側の関心事である可能性が高く、
M2統合時に二重リトライになるリスクがある。

## 決定

### 1. `ExecutionRequest`はdomain型として新設しない

`ExecutionAdapter`（domain）は§3.4疑似コードのとおり`execute(prompt: RenderedPrompt, policy: ExecutionPolicy): RawResponse`
のみを持つ。`ExecutionRequest`はM2で`ApapExecutionAdapter`（`prompt-engine-infrastructure`）を
実装する際に、そのモジュール内部の変換結果としてのみ登場させる。

### 2. ドメイン型の確定

```kotlin
// domain.execution
enum class ExecutionErrorType {
    CONNECT_TIMEOUT,   // 接続確立前のタイムアウト（未送信と断定できる、リトライ可）
    READ_TIMEOUT,      // 応答待機中のタイムアウト（先方で実行済み・課金済みの可能性を否定できない、リトライ不可）
    CONNECTION_FAILURE, // 接続自体が確立できなかった（未送信と断定できる、リトライ可）
    RATE_LIMITED,      // 429相当。実行前に拒否された（リトライ可）
    SERVER_ERROR,      // 5xx相当。プロバイダ側が明示的に失敗を返した（リトライ可）
    CLIENT_ERROR,      // 4xx相当（429除く）。リクエスト自体が不正でリトライしても変わらない（リトライ不可）
    UNKNOWN,           // 分類不能（安全側に倒し、リトライ不可）
}

data class Usage(val inputTokens: TokenCount, val outputTokens: TokenCount)

data class RawResponse(
    val content: String,
    val usage: Usage,
    val latency: LatencyMs,
    val retryCount: Int = 0,   // 最終的に成功する（または最終失敗する）までに消費したリトライ回数
)

class ExecutionFailedException(
    val errorType: ExecutionErrorType,
    val retryCount: Int,
    cause: Throwable? = null,
) : RuntimeException("EXECUTION_FAILED: errorType=$errorType retryCount=$retryCount", cause)

data class BackoffPolicy(
    val initialDelayMs: Long = 500,
    val multiplier: Double = 2.0,
    val maxDelayMs: Long = 8000,
) {
    // delayFor(attempt)は 1-based のリトライ回次を受け取り、
    // initialDelayMs * multiplier^(attempt-1) を maxDelayMs で頭打ちした値（ミリ秒）を返す
    fun delayFor(attempt: Int): Long
}

data class ParseRepairPolicy(
    val enabled: Boolean = false,
    val maxAttempts: Int = 2,   // 修復のための再実行回数の上限（初回parseは含まない）
)

data class ExecutionPolicy(
    val timeoutMs: Long,
    val maxRetries: Int = 2,
    val backoff: BackoffPolicy = BackoffPolicy(),
    val parseRepair: ParseRepairPolicy = ParseRepairPolicy(),
)

interface ExecutionAdapter {
    fun execute(prompt: RenderedPrompt, policy: ExecutionPolicy): RawResponse // throws ExecutionFailedException
}

data class ExecutionOutcome(
    val parsedOutput: ParsedOutput,
    val attempts: List<RawResponse>, // [0]=初回実行、[1..]=修復のための再実行（各自のusage/latency/retryCountを保持）
)

// domain.parsing
enum class OutputFieldType { STRING, NUMBER, BOOLEAN, ARRAY, OBJECT }

data class OutputSchemaField(val name: String, val type: OutputFieldType, val required: Boolean = false)

data class OutputSchema(val id: String, val fields: List<OutputSchemaField> = emptyList())

data class ParsedOutput(val format: OutputFormat, val fields: Map<String, Any?> = emptyMap(), val raw: String)

class ParseFailedException(
    val format: OutputFormat,
    val reason: String,     // 構造的な理由のみ（フィールド名・エラー種別等）。生のraw/contentを含めない
    val repairAttempts: Int = 0,
) : RuntimeException("PARSE_FAILED: $reason (format=$format, repairAttempts=$repairAttempts)")

interface OutputFormatter {
    fun format(): OutputFormat
    fun instruction(schema: OutputSchema?): String
    fun parse(raw: String, schema: OutputSchema?): ParsedOutput // throws ParseFailedException
}
```

`OutputSchema`はJSON Schema全体を表現しない。トップレベルの`必須フィールド`と`型`のみを検証する
最小限の構造的サブセットとする（`VariableDefinition.constraints`が`pattern:<regex>`等の
限定的な文字列表現に留めているのと同じ考え方）。ネストしたオブジェクト・配列要素の型検証は
対象外。これはJSON Schema全体を実装する工数・複雑度がP7のスコープに見合わないための判断であり、
将来より厳密な検証が必要になった場合は`OutputSchemaField`を拡張するか、専用の型を追加する
（非破壊的に拡張可能）。

`RawResponse`は§13.2の`usage {inputTokens, outputTokens, cost}`から`cost`を含めない。
`cost`は`ModelProfile.costPerToken`（既存、ADR-0013）と`usage`から呼出側が導出できる値であり、
`RawResponse`自身が保持する一次データではないため。

`RawResponse.content`は`ExecutionOutcome.attempts`を通じて修復ラウンドの失敗応答も含めて
保持される。これは意図的である（`SensitiveValue`と同じ設計思想、§2.9決定1参照）:
実行・監査に必要な生値は保持するが、**例外メッセージ・ログ相当の文字列化には含めない**
（決定7参照）。

### 3. `ExecutionRequest`は導入しない（決定1の繰り返し、影響範囲節に記載）

### 4. `OutputSchema`はP7では呼出側が明示的に渡す値とする

`RenderEngine.render()`に`outputSchema: OutputSchema? = null`を追加するが、この値は
`CompiledPrompt`から自動導出されない。DSLの`output:`ブロックを`CompiledPrompt`へ載せる作業は
P8（Pipeline Orchestrator）のスコープとし、GitHub Issue #32
「P8: DSLのoutputブロックをCompiledPromptに載せる」で追跡する。`schemaRef`解決のための
Schema Repository相当のInterfaceの要否も同Issueで検討する。

### 5. `RenderEngine`と`OutputFormatter`の接続

`RenderEngine.render()`のシグネチャを次のように変更する。

```kotlin
interface RenderEngine {
    fun render(
        compiled: CompiledPrompt,
        variableBindings: BindingSet,
        contextBindings: ContextBindingSet,
        outputFormat: OutputFormat,
        outputSchema: OutputSchema? = null,
    ): RenderedPrompt
}
```

`RenderEngineImpl`は`outputFormatters: Map<OutputFormat, OutputFormatter>`をコンストラクタ注入され
（§5.7シーケンスで`RE`が`OF`を直接呼ぶため、呼出元は`format`のみを渡し`OutputFormatter`実体は
渡さない）、`outputFormat`に対応する`OutputFormatter`の`instruction(outputSchema)`を呼ぶ。

挿入位置: `instruction()`の返り値が空文字なら何もしない（`TextOutputFormatter`は常に空文字を返す。
無意味な空行や`renderHash`の変化を避けるため）。空文字でなければ、`messages`中の最初の
`role == SYSTEM`のメッセージがあればその`content`末尾に改行区切りで追記し、無ければ新規の
`SYSTEM`メッセージとして`messages`末尾に追加する。挿入後に`renderHash`を算出する（既存のsensitive値
マスクなしhash計算方針は変更しない）。

この挿入規則は「著者がDSLで書いたsystem blockに指示文を合流させ、無ければ独立したsystem
メッセージとして送る」という単純な規則であり、複数のsystemメッセージが既に存在する場合の
マージ規則等の複雑なケースはM1のBlockNode→messages変換規則（ADR-0013決定3、同一role重複を
想定しない）と整合的に、最初の1件のみを対象とする。

### 6. parseRepairの再実行メッセージ構築とrenderHash計算の共有

`RenderEngineImpl`のハッシュ計算ロジックを`RenderHashCalculator`（`prompt-engine-core`、
`engine.render`パッケージ、`internal`）として抽出し、`RenderEngineImpl`・修復ラウンド構築処理の
両方から再利用する。修復ラウンドは以下の`RenderedPrompt`を新規に構築し、これを
`ExecutionAdapter.execute()`に渡す。

```text
messages = 元のRenderedPrompt.messages
  + RenderedMessage(ASSISTANT, 直前の失敗応答.content)
  + RenderedMessage(USER, "先の応答は指定した形式で解釈できませんでした（理由: <構造的な理由>）。"
                          + formatter.instruction(schema)
                          + "上記の形式を厳守して再出力してください。")
renderHash = RenderHashCalculator.compute(messages, outputFormat, engineId = "pe-repair/1", engineVersion)
```

`engineId`を元の`templateEngine.id()`（例: `pe-tmpl/1`）ではなく固定値`"pe-repair/1"`にするのは、
修復ラウンドの`RenderedPrompt`はTemplateEngineによるAST展開を経ておらず、「どのRender経路で
生成されたか」を`renderHash`の入力からも区別可能にするため。

修復再実行の責務は`ExecutionCoordinator`（`prompt-engine-core`、`engine.execution`パッケージ、
新設・非SPI・具象クラス）が持つ。§16拡張ポイント一覧に「Execution/Response Parsing全体を
統括するEngine」は定義されていないため、これはP6の`RenderEngine`と同様の位置づけ
（§3.4疑似コードには無いが、Pipeline Orchestrator（P8）が「既存のEngineに委譲するだけの薄い層」
であるためには、Execution+Response Parsingを結合する具象クラスがP7時点で必要）で新設する。
`ExecutionCoordinator`は`ExecutionAdapter`・`Map<OutputFormat, OutputFormatter>`・
`TokenizerPlugin`を注入され、初回実行→parse→（失敗時）修復再実行→再parseのループを
`ExecutionPolicy.parseRepair`に従って回し、最終的に`ExecutionOutcome`を返す
（成功時）か`ParseFailedException`を投げる（`parseRepair.maxAttempts`を使い切っても失敗した場合）。

**修正（ADR-0015、supersedeではなく本決定への修正）**: 上記「§16拡張ポイント一覧に
定義が無いためdomain Interfaceを持たない」という判断は、Interfaceをdomainに置く理由を
「§16の拡張ポイントであること」の1点のみに絞ったために生じた。ADR-0015はこれを、
「(a) §16拡張ポイントであること」と「(b) 上位レイヤ（Pipeline Orchestrator、P8）の
依存性逆転」という2つの独立した理由があり、後者だけを理由にdomain Interfaceを
置くことも正当である、と修正した。この結果、P8で`domain.execution.ExecutionEngine`
Interfaceを新設し、`ExecutionCoordinator`はそれを実装する（クラス自体・シグネチャは
変更しない）。詳細はADR-0015第1節を参照。

### 7. リトライの責務境界とエラー分類

M1（P7時点、実APAP接続なし）では、**PE側（`RetryingExecutionAdapter`、`prompt-engine-core`、
`engine.execution`パッケージ）が`ExecutionPolicy`に基づき一元的にリトライする**。

リトライ可否は`ExecutionErrorType`で判定する:

| errorType | リトライ | 理由 |
|---|---|---|
| CONNECT_TIMEOUT | 可 | 接続確立前。先方に未送信と断定できる |
| CONNECTION_FAILURE | 可 | 接続自体が確立できていない。先方に未送信と断定できる |
| RATE_LIMITED | 可 | 実行前に拒否された（429相当） |
| SERVER_ERROR | 可 | プロバイダ側が明示的に失敗を返した（5xx相当） |
| READ_TIMEOUT | **不可** | 応答待機中のタイムアウト。先方で実行済み・課金済みの可能性を否定できない |
| CLIENT_ERROR | 不可 | リクエスト自体が不正（429除く4xx相当）。再送しても結果は変わらない |
| UNKNOWN | 不可 | 分類不能。安全側に倒す |

`RetryingExecutionAdapter`は`ExecutionAdapter`を実装するDecorator（任意の`ExecutionAdapter`を
ラップ）とし、`policy.maxRetries`到達または非リトライ対象エラーで打ち切り、
`policy.backoff.delayFor(attempt)`の待機を挟んで再実行する。待機処理は`sleeper: (Long) -> Unit`
として注入可能にし（既定は`Thread::sleep`）、テストでは即時実行の実装に差し替えて決定的・高速に
検証する（ADR-0013が`engine.render`/`engine.optimization`で時刻・乱数APIの直接使用を禁じているのと
同じ精神で、`engine.execution`でも待機処理を抽象化する）。最終的に成功した場合、
`RawResponse.retryCount`にはリトライ回数（0-based、初回成功なら0）を格納する。

**M2に向けた責務境界の懸念（未解決・追跡対象）**: APAPの責務（設計書§2.1・実装ガイド§1.1、
プロバイダ/モデル抽象化・ルーティング）を踏まえると、RATE_LIMITED / SERVER_ERRORのリトライは
本来APAP側の関心事である可能性が高い。M1では実APAP接続が無いためPE側で一元化するが、
M2でAPAP統合時に二重リトライ（同一エラーに対しAPAP側・PE側の両方が独立にリトライし、
再送回数・レイテンシ・課金が想定を超える）が起きないよう、責務を再確認する必要がある。
GitHub Issue #31「APAP統合時にリトライ責務の重複を解消する」で追跡する。

### 8. parseRepairの既定値とコスト記録

- 既定値: `ParseRepairPolicy(enabled = false, maxAttempts = 2)`。無効化が既定なのは、
  修復再実行が追加の課金・レイテンシを伴う操作であり、呼出側が明示的に許可した場合のみ
  行うべきという判断（`ExecutionPolicy`全体の既定が「安全側」であることと整合）。
  有効化した場合の既定試行回数2は、1回では「たまたま外れた」ケースを救えず、
  無制限では課金が青天井になるため、実用上のバランスとして採用する。
- 修復にかかったトークン・コストは、`OptimizationReport`相当の専用型を新設せず、
  `ExecutionOutcome.attempts`（初回実行＋各修復再実行の`RawResponse`のリスト。各自が
  `usage`/`latency`/`retryCount`を保持）で表現する。Audit/Evaluation側はこのリストを合算すれば
  トークン・コストを追跡できるため、`OptimizationReport`のような専用集約型は不要と判断した。
  ただし`attempts`に載る`content`自体の扱いは決定9の訂正を参照（解析に失敗し破棄された
  中間の応答はマスクする）。

### 9. 秘密情報の非露出（修復ループ経由）

修復ラウンドの`RenderedMessage(ASSISTANT, 直前の失敗応答.content)`は**意図的に生値を保持する**
（モデルが自身の直前の出力を見て訂正するために必須。`SensitiveValue`が実行に必要な生値を
`content`に残す方針、ADR-0013決定1と同じ考え方）。一方、以下は構造的にraw contentを含まない
よう型・実装両面で徹底する:

- `ParseFailedException.reason`は`OutputFormatter`実装が構造的な理由（フィールド名・エラー種別・
  JSON構文エラーである旨等）のみを設定する契約とする（KDocで明記）。`JsonOutputFormatter`は
  JSON構文エラー時にJacksonの例外メッセージ（入力の一部を含みうる）をそのまま転記せず、
  固定文字列`"invalid JSON syntax"`を使う。
- `ExecutionFailedException`・`ParseFailedException`はいずれも生のresponse content/promptを
  コンストラクタ引数に持たない（型として構造的に持てない）。
- `ExecutionCoordinator`が最終失敗時に投げる`ParseFailedException`のreasonは、直前の
  `OutputFormatter.parse()`が投げた`ParseFailedException.reason`をそのまま引き継ぐ
  （既に構造的な理由のみのため、再度の伝播でも生値は混入しない）。

これらは経路別テスト（P4/P6と同じ形式）で固定する: 生成した秘密情報マーカーを含む
fake応答で修復ループを最終失敗まで走らせ、最終的にthrowされる`ParseFailedException.message`に
マーカーが含まれないことを検証する。

**訂正（レビューで発見、初版の見落とし）**: 初版の実装は`ExecutionOutcome.attempts`（決定8、
Audit/Evaluation向けの記録データ）に、解析に失敗し破棄された中間の応答の`RawResponse.content`を
生値のまま格納していた。これは「プロバイダには送るが記録には残さない」という要求
（P7発注時の指示）に反する。`RenderedMessage(ASSISTANT, ...)`は実行のためプロバイダへ送る経路
であり生値保持が正しいが、`attempts`はAudit/Evaluationが読み取る記録用データであり、
この2つの経路を混同していたことが原因（ADR-0007がP4で確立した「値を秘匿する経路を後から
個別に用意するより、構築時点で不正な組み合わせを型・不変条件で排除する」方針に対し、本ADR初版は
`RawResponse`という単一の型を実行用途と記録用途の両方に使い回したことで、後者の要求を見落とした）。

`ExecutionCoordinator`を修正し、`attempts`へ追加する直前に、解析へ失敗した応答の`content`を
`"***"`（`SensitiveValue.toString()`と同じマスク表現）へ置換する。最終的に解析へ成功した応答
（`attempts.last()`）のみ実値の`content`を保持する。`ExecutionOutcome`のKDocにこの契約
（`attempts`の`content`は生成側がマスクする）を明記した。

漏洩経路テストは以下を独立した経路として個別に検証する（1つのテストで済ませない。
P2で「マスク処理の書き込み箇所を1つ見落とした」前例があるため）:

1. `ExecutionCoordinatorTest`「漏洩経路1」: 最終`ParseFailedException.message`/`.reason`
2. `ExecutionCoordinatorTest`「漏洩経路2」: 修復ラウンドの`RenderedPrompt`（プロバイダへの送信経路）
   は意図通り生値を保持すること（陽性対照）
3. `ExecutionCoordinatorTest`「漏洩経路3」: 実行成功時、`RawResponse.content`に秘密情報が
   含まれていても例外は発生せず結果にのみ渡ること
4. `ExecutionCoordinatorTest`「漏洩経路4」: 修復成功時の`ExecutionOutcome.attempts`が、
   破棄された失敗応答の`content`を記録しないこと（本訂正の対象）
5. `JsonOutputFormatterTest`「漏洩経路」（構文エラー・型不一致）: `OutputFormatter`実装単体での
   例外メッセージ
6. `JsonOutputFormatterTest`「漏洩経路」（cause連鎖）: Jacksonの例外を`cause`として連鎖させて
   いるため（本ADR決定9のJSON構文エラー処理）、`cause`チェーンを辿ってもJacksonの例外メッセージ
   経由で生値が含まれないことを検証する（Jackson 2.16+の既定では`StreamReadFeature.
   INCLUDE_SOURCE_IN_LOCATION`が無効化されており含まれないが、既定値への依存を暗黙のままに
   せず、将来の設定変更に対する回帰検知として固定する）

## 影響範囲

- `prompt-engine-domain`: `domain.execution`（`ExecutionErrorType`/`Usage`/`RawResponse`/
  `ExecutionFailedException`/`BackoffPolicy`/`ParseRepairPolicy`/`ExecutionPolicy`/
  `ExecutionAdapter`/`ExecutionOutcome`新設）、`domain.parsing`（`OutputFieldType`/
  `OutputSchemaField`/`OutputSchema`/`ParsedOutput`/`ParseFailedException`/`OutputFormatter`新設）、
  `domain.render.RenderEngine`（`outputSchema`引数追加、既存シグネチャ変更）
- `prompt-engine-core`: `engine.render`（`RenderHashCalculator`抽出、`RenderEngineImpl`の
  `outputFormatters`注入・instruction挿入ロジック追加）、`engine.formatter`
  （`TextOutputFormatter`新設）、`engine.execution`（`RetryingExecutionAdapter`・
  `ExecutionCoordinator`新設）
- `plugins/execution-fake`（新設Gradleサブプロジェクト）: `FakeExecutionAdapter`
  （`promptengine.plugin.execution.fake`、ADR-0003準拠）
- `plugins/formatter-json`（新設Gradleサブプロジェクト）: `JsonOutputFormatter`
  （`promptengine.plugin.formatter.json`、ADR-0003準拠）。JSON解析に`jackson-databind`を
  単体で追加する（Spring BOM管理下ではないため`gradle/libs.versions.toml`にバージョンを
  明示指定する。既存のsnakeyaml追加と同じ方針）
- `prompt-engine-bootstrap`: `build.gradle.kts`に
  `testImplementation(project(":plugins:execution-fake"))`・
  `testImplementation(project(":plugins:formatter-json"))`追加（ArchitectureTestの規約6検証対象に含めるため）
- GitHub Issue #31（APAP統合時のリトライ責務重複、tech-debt）・Issue #32
  （P8でのDSL output:ブロック回収、tech-debt）を作成
- 設計書§2.6（ステージ9・10の注記にADR-0014参照を追加）、§2.9（instruction注入経路の記述を追加）、
  §4.4（VO一覧に`ExecutionPolicy`/`RawResponse`/`ParsedOutput`/`OutputSchema`を追加）に
  本ADRの参照注記を追記

## 参照

- [PromptEngine_設計書.md §2.1 / §2.6 / §2.9 / §3.4 / §4.4 / §5.7 / §5.8 / §5.9 / §13.2 / §13.3 / §15.1 / §16](../PromptEngine_設計書.md)
- [ADR-0003: Plugin実装のパッケージ命名規則](0003-plugin-package-naming.md)
- [ADR-0007: sensitive変数のリテラルdefault禁止](0007-sensitive-variable-no-literal-default.md)
- [ADR-0013: Optimization Engine / Render Engine（P6）](0013-optimization-render-engine.md)
- GitHub Issue #31, #32
