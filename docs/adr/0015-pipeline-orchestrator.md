# ADR-0015: Pipeline Orchestrator（P8）のレイヤ配置・ドメイン表現を確定する

## ステータス

Accepted

## コンテキスト

P8（Pipeline Orchestrator）実装にあたり、実装ガイド§6.9・設計書§2.6（Pipeline 12ステージ）・
§2.3〜2.4（レイヤ/モジュール責務）・§3.3〜3.4・§10（アクティビティ図）・§13.3（エラー仕様）・
§14（イベント一覧）・§16（拡張ポイント）を横断しても、以下が未決／実装と食い違ったままでは
着手できないことが分かった。事前協議（本セッション）で確認済み。
[[feedback-adr-before-domain-changes]]の方針に従い、実装前に本ADRを起票する。

### 1. ステージ実装の配置先が資料間で矛盾する

実装ガイド§6.9は「`prompt-engine-application`にPipelineを実装します」と指示するが、
設計書§2.4（モジュール構成・責務一覧）は「Prompt Core | Pipeline全体の統括。
ステージ実行順序・エラー処理・トレース | 全Engine」とあり、Pipeline全体の統括を
Prompt Core（`prompt-engine-core`、CLAUDE.md「Pipeline全体を統括する内部コンポーネントは
Prompt Core（モジュール`prompt-engine-core`）と呼び」）に割り当てる。一方、設計書§3.3
（主要クラス）は「PipelineOrchestrator | Application | Stage列の構築・実行・計測」と、
クラス単位ではApplicationに割り当てる。§2.4と§3.3が矛盾している。

さらに、`prompt-engine-application`はCLAUDE.mdの絶対規約により`prompt-engine-domain`
のみに依存でき、`prompt-engine-core`（Engine実装群）には依存できない
（ArchUnit `ArchitectureTest`で機械的に強制）。ステージが実際にEngineへ委譲する
実装である以上、ステージの依存先がdomain Interfaceのみで完結するかどうかが、
配置先を決める上での制約になる。

### 2. `ExecutionCoordinator`（P7）がdomain Interfaceを持たない

P3c〜P6で確立された全Engine（`CompositionService`/`VariableResolverChain`/
`ContextResolver`/`ValidationEngine`/`OptimizationEngine`/`RenderEngine`）は、
domainにInterfaceを持ち`prompt-engine-core`が実装する、という一貫した形を取る。
一方P7の`ExecutionCoordinator`は、ADR-0014決定6で「§16拡張ポイント一覧に
『Execution/Response Parsing全体を統括するEngine』は定義されていないため、
§3.4疑似コードには存在しない...非SPI・具象クラスとして新設する」と明記し、
意図的にdomain Interfaceを持たない具象クラスとした。

このため、Execution(ステージ9)・Response Parsing(ステージ10)に対応するステージ実装が
`ExecutionCoordinator`を直接参照する限り、そのステージは`prompt-engine-core`への
依存を必要とし、コンテキスト1の制約と衝突する。

### 3. §13.3のエラー仕様表に`RENDER_ERROR`が無い

設計書§2.6ステージ8（Rendering）の失敗時コードは`RENDER_ERROR`だが、§13.3の
HTTP/codeマッピング表にはその行が存在しない。P8はStageError→errorCodeの写像を
1箇所に集約する実装を求められており（実装ガイド§6.9）、`RENDER_ERROR`のHTTP分類が
未定義のままでは集約先の写像表を完成させられない。

### 4. Stage 11（Evaluation）・Stage 12（Audit）が依存する型が未実装

Stage 11は「イベント発行のみ（非同期評価）」を求められるが、発行先となる
`EventBusAdapter`（設計書§16拡張ポイント#14）はInterfaceも実装も存在しない。
Stage 12は`AuditRepository.append(AuditRecord): void`（§3.4疑似コード）を必要とするが、
`AuditRecord`/`AuditRepository`のいずれも存在しない。

### 5. `output:`宣言（§15.1、Issue #32）をどこまでP8で回収するか

Issue #32は「DSLの`output:`ブロックが`CompiledPrompt`に載っていない」ことを指摘した。
`validation:`宣言（§15.7、ADR-0012）が`PromptVersion.validation` → `CompiledPrompt.validation`
という確立された経路を持つのに対し、`output:`には対応する型も経路も無い。
一方、`schemaRef`が指す実体（`OutputSchema`、ADR-0014）を解決する`SchemaRepository`相当の
Interfaceは設計書のどこにも定義されていない。

### 6. Audit失敗時のログ出力とP4のフレームワーク隔離規約が衝突しうる

設計書§2.6ステージ12は「Auditステージは失敗させない。失敗時はDLQへ退避してログに記録」と
定める。ステージ実装は本ADR決定1により`prompt-engine-application`に置くが、
`prompt-engine-application`はP4で追加したArchUnit規約（`org.slf4j..`への依存を、
`org.springframework.transaction.annotation..`と並ぶ例外なしに禁止）により、
SLF4Jを直接使えない。

## 決定

### 1. ステージ配置: `ExecutionEngine`をdomainへ追加し、12ステージすべてを`prompt-engine-application`に置く

`ExecutionCoordinator`が実装するdomain Interfaceを新設する。

```kotlin
// domain.execution
interface ExecutionEngine {
    fun run(
        rendered: RenderedPrompt,
        policy: ExecutionPolicy,
        schema: OutputSchema?,
        budget: TokenCount,
    ): ExecutionOutcome // throws ExecutionFailedException, ParseFailedException, TokenBudgetExceededException
}
```

`ExecutionCoordinator : ExecutionEngine`（`prompt-engine-core`、既存クラスへの追加実装のみ、
シグネチャ変更なし）。ADR-0014決定6を本ADRで修正する（第9節参照）。

これにより、Stage 1〜12すべてが「domain Interfaceのみに依存する薄い委譲層」という
均一な形になり、§3.3の`PipelineOrchestrator | Application`、実装ガイド§6.9の
「`prompt-engine-application`にPipelineを実装します」の両方と整合する
（§2.4の「Prompt Core」記述は、モジュール一覧としての粗い要約であり、§3.3のクラス単位の
割当てより解像度が低い。クラス単位の記述を優先する）。

**同じ原則の追加適用**: 実装に着手した際、Stage 5（Resolve Context）が委譲する
`ContextResolverImpl`（P4、7スコープをまとめるファサード）も`ExecutionCoordinator`と
同じ状態（domain Interfaceを持たない具象クラス）であることが判明した。
[VariableResolverChain][promptengine.domain.variable.VariableResolverChain]（P4で
既にdomain Interface化済み）との対称性を欠いた実装漏れであり、`ExecutionEngine`と
同一の理由（上位レイヤの依存性逆転）で`domain.context.ContextResolverChain`を新設し、
`ContextResolverImpl`に実装させる。

**原則（本ADRで確立し、以降のPhaseにも適用する）**: domainにInterfaceを置く理由は、
(a) §16の拡張ポイントである（差替可能性を設計として担保する必要がある）、
(b) 上位レイヤの依存性逆転（上位レイヤが下位レイヤの具象実装に直接依存しないようにする）、
の2つがある。ADR-0014決定6は前者のみを判断基準にしたため`ExecutionCoordinator`を
具象クラスに留めたが、後者だけを理由にInterfaceを置くことも正当である。本ADRの
`ExecutionEngine`は後者（Pipeline層がEngine実装に直接依存しない）を理由に新設する
拡張ポイントではないInterfaceの最初の例である。

`prompt-engine-application`の`build.gradle.kts`は`prompt-engine-domain`のみをmain依存に持つ
（既存のArchUnit規約のまま変更しない）。ステージ実装が必要とする具象Engineインスタンス
（`RenderEngineImpl`・`ExecutionCoordinator`等）は、テストでは各テストが直接構築し、
実運用の配線はP9でCLAUDE.md「具象クラスのDI結線は`prompt-engine-bootstrap`の
Configurationクラスでのみ行う」に従って行う（本ADRのスコープ外）。

### 2. Stage 9・10の実装形: `ExecutionEngine.run()`への統合を12ステージの枠組みで表現する

ADR-0014決定6が確立した通り、`ExecutionEngine.run()`は実行(ステージ9)と解析(ステージ10)を
1回の呼び出しで行う（修復ラウンドを内包するため、ステージ境界で分離できない）。
`ExecutionStage`が`ExecutionEngine.run()`を呼び、その結果（`ExecutionOutcome`、
`attempts`+`parsedOutput`を保持）を`PipelineContext.executionOutcome`へ格納する。
`ResponseParsingStage`は`context.executionOutcome`から`parsedOutput`を
`PipelineContext.parsedOutput`へ転記するのみの、実質的な素通しステージとする
（実装ガイド§6.9「各ステージは既存のEngineに委譲するだけの薄い層にすること」を、
ADR-0014が既に確立した統合設計の上でも12ステージの構造を保つ形で満たす）。

**同じ原則をStage 2（Merge）・Stage 3（Import）にも適用する**: `CompositionService.compile()`
（P3c、ADR-0009/0010）は、extends解決・import/include解決・macro展開を1回の呼び出しで
まとめて行う設計が既に確立しており、ステージ境界で分離できない点がExecution/Response
Parsingと同型である。`MergeStage`が`compositionService.compile(promptKey, promptVersion,
mode)`を呼び、結果の`CompiledPrompt`（循環検出等のImport関連エラーも`compile()`内で検出済み）
を`PipelineContext.compiled`へ格納する。`ImportStage`は素通しステージとする
（`compile()`が既に処理を終えているため、追加で行うことは無い）。

### 3. `PipelineContext`・`PipelineStage`・`PipelineMode`（domain）

```kotlin
// domain.pipeline
enum class PipelineMode { RENDER_ONLY, FULL_EXECUTION, COMPILE_ONLY }

data class PipelineContext(
    val request: PipelineRequest,       // PromptKey, VersionRef, 呼出パラメータ, 呼出時outputFormat/outputSchema, 呼出時budget等
    val promptVersion: PromptVersion? = null,
    val compiled: CompiledPrompt? = null,
    val variableBindings: BindingSet? = null,
    val contextBindings: ContextBindingSet? = null,
    val validationReport: ValidationReport? = null,
    val rendered: RenderedPrompt? = null,
    val executionOutcome: ExecutionOutcome? = null,
    val parsedOutput: ParsedOutput? = null,
    val traceId: String,
)

interface PipelineStage {
    val name: String
    fun execute(context: PipelineContext): PipelineContext // throws StageError系のdomain例外
}
```

§3.4疑似コードの`PipelineContext`は`ast`/`rawResponse`のように現行実装と乖離した
フィールド名を持つため、そのまま採用せず、既存の確立済み型（`CompiledPrompt`・
`ExecutionOutcome`等）に合わせて読み替える（ADR-0012が`ExpandedAst`を
`CompiledPrompt.body`に読み替えたのと同じ扱い）。`PipelineContext`はdata classとして
不変更新（`copy()`で次ステージ用の新インスタンスを作る）とする。

### 4. StageErrorマッピングとステージ⇔エラーコード対応表

`PipelineOrchestrator`がステージ実行を`try/catch`し、投げられた例外の型から
§13.3のエラーコードへ変換する1箇所（`StageErrorMapper`、`prompt-engine-application`）に
集約する。

| # | Stage | 例外型 | errorCode | HTTP（§13.3、参考） |
|---|---|---|---|---|
| 1 | Load | `PromptVersionNotFoundException` | `PROMPT_NOT_FOUND` | 404 |
| 2 | Merge | `TemplateReferenceNotFoundException`（`CompositionException`、ADR-0009） | `TEMPLATE_NOT_FOUND` | 404 |
| 3 | Import | `CircularDependencyException` / `FragmentReferenceNotFoundException`（いずれも`CompositionException`、ADR-0009） | `CIRCULAR_DEPENDENCY` / `FRAGMENT_NOT_FOUND` | 400 / 404 |
| 4 | Resolve Variables | `VariableUnresolvedException` | `VARIABLE_UNRESOLVED` | 422 |
| 5 | Resolve Context | `ContextUnavailableException`（required宣言のみ） | `CONTEXT_UNAVAILABLE` | 422 |
| 6 | Validation | `ValidationFailedException`（新設。`ValidationEngine.validate`自体は例外を投げないため
（ADR-0012決定1）、`ValidationStage`が`ValidationReport.hasErrors`を見てこの例外を投げる） | `VALIDATION_FAILED` | 400 |
| 7 | Optimization | `TokenBudgetExceededException` | `TOKEN_BUDGET_EXCEEDED` | 422 |
| 8 | Rendering | `IllegalStateException`（未登録`OutputFormatter`等、実装内部エラー） | `RENDER_ERROR` | 500（決定5参照） |
| 9 | Execution | `ExecutionFailedException` | `EXECUTION_FAILED` | 502 |
| 10 | Response Parsing | `ParseFailedException` | `PARSE_FAILED` | 400 |
| 11 | Evaluation | （失敗させない。例外を握り潰し記録のみ） | なし | - |
| 12 | Audit | （失敗させない。`AuditFailureHandler`へ委譲） | なし | - |

`StageErrorMapper`（`prompt-engine-application`）は例外の型を第一の判定基準とし、
`IllegalStateException`のように型だけでは`RENDER_ERROR`か汎用`INTERNAL_ERROR`かを
区別できない場合に限り、どのステージが投げたか（ステージ名）を補助的な判定基準とする
（1箇所への集約は保ちつつ、型のみでは表現しきれない分類を扱う）。`CompositionException`の
サブタイプのうち上記3種以外（深さ超過・サイズ超過等）は§13.3にコードが定義されていない
ため（`CompositionException`自身のKDoc参照）、`INTERNAL_ERROR`にフォールバックする。

### 5. `RENDER_ERROR`の追加とHTTP分類根拠

設計書§13.3に以下の行を追加する。

```
| 500 | RENDER_ERROR / INTERNAL_ERROR |
```

Validationステージ（6）を通過した時点で、束縛済みAST・呼出パラメータ・宣言済み
Contextはすべて検証済みである。その後段のRendering（8）が失敗するのは、
「未登録の`OutputFormatter`」のように、呼出パラメータの不備ではなくEngine/Plugin側の
構成不備・実装不具合に起因するケースに限られる。クライアント起因（4xx）ではなく
サーバ起因（5xx）として扱う。

### 6. `PipelineFactory`

```kotlin
// application
class PipelineFactory(private val stages: List<PipelineStage>) {
    // stagesは12件、§2.6の順序で全種類を1つずつ受け取る（コンストラクタで検証）
    fun stagesFor(mode: PipelineMode): List<PipelineStage> = when (mode) {
        PipelineMode.RENDER_ONLY -> stages.take(8)       // 1〜8
        PipelineMode.FULL_EXECUTION -> stages            // 1〜12
        PipelineMode.COMPILE_ONLY -> stages.take(3) + stages[5] // 1〜3 + 6(Validation)
    }
}
```

`stages`の並びは`prompt-engine-bootstrap`（P9スコープ）が構築するが、P8時点のテストでは
テストコードが直接12個のステージインスタンスを順序通り構築して渡す。

### 7. `EventBusAdapter` / `AuditRepository` / `AuditRecord` / `AuditFailureHandler`（domain）、最小実装（infrastructure）

```kotlin
// domain.event
interface EventBusAdapter {
    fun publish(event: DomainEvent)
}

// domain.audit
data class AuditRecord(
    val traceId: String,
    val promptKey: String?,
    val mode: PipelineMode,
    val stageDurationsMs: Map<String, Long>,
    val outcome: AuditOutcome,          // Success または Failure(errorCode: String)
    val occurredAt: Instant,
)

sealed interface AuditOutcome {
    data object Success : AuditOutcome
    data class Failure(val errorCode: String) : AuditOutcome
}

interface AuditRepository {
    fun append(record: AuditRecord)
}

interface AuditFailureHandler {
    fun handle(record: AuditRecord, cause: Throwable)
}
```

`AuditRecord`は`RawResponse.content`/`RenderedMessage.content`のような生のprompt/response
内容を一切保持しない（traceId・promptKey・ステージ所要時間・成否・errorCodeのみ）。
Auditが記録すべき「全ステージ記録」の生データ（§2.6ステージ12「入力: 全ステージ記録」）は
将来Issue #35で`AuditRepository`を本実装に差し替える際、`AuditRecord`自体を拡張するか、
別途Audit専用のシリアライズ経路を設けるかを検討する（P8では構造化メタデータのみを扱う）。

§3.4疑似コードの`AuditRepository.search(q: AuditQuery): Page<AuditRecord>`は、
`AuditQuery`/`Page<T>`のいずれも未設計であり、P8のAuditステージは`append`のみを必要とする
ため、本ADRでは`append`のみを持つInterfaceとして新設する。`search`はIssue #35で
`AuditQuery`/`Page<T>`と合わせて設計・追加する。

最小実装（`prompt-engine-infrastructure`、Issue #35で本実装へ置換予定）:

```kotlin
// infrastructure.messaging（EventBusAdapter）/ infrastructure.audit（AuditRepository）
class InMemoryEventBusAdapter(activeProfiles: Set<String>) : EventBusAdapter { ... }
class InMemoryAuditRepository(activeProfiles: Set<String>) : AuditRepository { ... }
```

実装名に用途を明示する（`InMemory`接頭辞）。コンストラクタで受け取る`activeProfiles`に
`"production"`が含まれる場合、`IllegalStateException`を起動時に投げる
（警告ログではなくエラーとする。特に`AuditRepository`はプロセス再起動で監査記録が
失われるため、本番での誤選択は監査要件の欠落という重大な問題になり、警告では
見逃されうる。`EventBusAdapter`も一貫性のため同じ扱いとする）。`activeProfiles`は
Spring `Environment`への直接依存を避け、`Set<String>`として受け取る（`prompt-engine-bootstrap`
がDI結線時に`Environment.activeProfiles`から変換して渡す想定、配線自体はP9スコープ）。

### 8. Audit失敗時のログ出力: domainに抽象を置き、実装はinfrastructureに置く

決定7の`AuditFailureHandler`（domain）がこの抽象を担う。`AuditStage`
（`prompt-engine-application`）は`auditRepository.append(record)`が例外を投げた場合、
`auditFailureHandler.handle(record, exception)`を呼ぶだけで、SLF4Jはじめ
いかなるログAPIも直接参照しない。P4で追加したArchUnitのフレームワーク隔離規約
（`prompt-engine-application`は`org.springframework.transaction.annotation..`のみを
例外として他のフレームワークに依存しない）は緩めない。

実装（`Slf4jAuditFailureHandler`、`prompt-engine-infrastructure`）はSLF4Jで構造化
（key=value）ログを出力する。`cause`は`javaClass.simpleName`のみを文字列補間し、
`cause.message`は補間せずSLF4Jの`(msg, throwable)`オーバーロードにそのまま渡す
（スタックトレースはログ基盤側で記録されるが、例外メッセージを組立文字列に混入させない。
`AuditRecord`自体が生のprompt/response内容を保持しないため（決定7）、通常は
`cause.message`が秘密情報を含む経路も無いはずだが、インフラ層由来の例外
（DB接続文字列等）が将来混入する可能性に備え、構造化ログのメッセージ本文には
`AuditRecord`のフィールドのみを載せる契約とする）。

実DLQ（キュー・再試行テーブル）はM1で実装しない。Issue #37で追跡する。

### 9. `output:`宣言の`PromptVersion`/`CompiledPrompt`への反映

```kotlin
// domain.render
data class OutputDeclaration(
    val format: OutputFormat,
    val schemaRef: String? = null,
)
```

`PromptVersion.output: OutputDeclaration? = null`（`output:`ブロック自体が宣言されなければ
`null`）を、`validation: ValidationSettings`（ADR-0012）と同じ配線パターンで追加する:
`NewPromptVersion.output` → `Prompt.create`/`newVersion`/`restore`が`PromptVersion`へ
引き継ぐ → `PromptVersionMemento.output` → `EventStorePromptRepository`が`output`列
（JSON、`validation`列と同じ方式）で永続化する。マイグレーション`V6__output_declaration.sql`
で`prompt_versions.output JSON`列を追加する。設計書§12 ER図に

```
output : JSON  ' OutputDeclaration（format/schemaRef）。ADR-0015で追加
```

を追記する。

`CompositionServiceImpl`は`CompiledPrompt.output = promptVersion.output`をそのまま
引き継ぐ（`validation`と同じ、ADR-0012決定2の前例）。

DSLフロントマター`output:`（§15.1）から`OutputDeclaration`への変換は`OutputFieldMapper`
（`prompt-engine-core`の`engine.compiler`、`ValidationFieldMapper`と同型）が担う。
`ValidationFieldMapper`同様、実際にDSL取り込み（authoring/ingestion）コマンドから
呼び出す配線はまだ存在しない（ingestion用のUseCase/CommandHandlerが未実装のため、
P9以降のスコープ）。P8では変換ロジックとその単体テストのみを用意する。

**優先順位**: Pipeline（`RenderingStage`）が使う実効`outputFormat`は
`呼出パラメータで明示指定された値 ?: compiledPrompt.output?.format ?: OutputFormat.TEXT`と
する。§2.8 Variable ResolutionのResolver Chainが既に「同名変数は先勝ち（明示パラメータ
最優先）」という原則を確立しており、それと同じ考え方をoutputFormatにも適用することで
一貫性を保つ。

`outputSchemaRef`から実際の`OutputSchema`（ADR-0014）を解決する経路は本ADRのスコープ外
とする（`SchemaRepository`相当のInterfaceが未設計、Issue #36で追跡）。Pipelineの
`outputSchema: OutputSchema?`は、P8時点では従来通り呼出側が明示的に渡す値のみを使う
（ADR-0014の既存方針を変更しない）。

### 10. Compiled-onlyモード・Render-onlyモードでのcontext未充足フィールド

`PipelineContext`の各フィールドは、モードに応じて一部が`null`のまま返る
（例: Compile-onlyは`rendered`/`executionOutcome`/`parsedOutput`が常に`null`）。
呼出元（テスト、将来のP9 REST層）は`PipelineMode`に応じてどのフィールドが
入っているかを理解した上で参照する契約とし、`PipelineContext`自体はモードを
知らない（Stage側もOrchestrator側も、どのモードで呼ばれているかによって
異なるフィールドを埋める・埋めないだけで、`PipelineContext`の型定義は
全モード共通の1つで良い）。

### 11. キャッシュを持たない

設計書§2.6ステージ1・§5.1〜5.2はPrompt Cacheの利用を前提とするが、Issue #15
（Template/Fragment Domain Event未実装）が未解決のため、キャッシュを無効化する
手段（Event購読によるキャッシュ無効化、§14 `CacheInvalidated`）が無い。P8は
キャッシュ無しで12ステージを通す。Stage 1（Load）は`PromptRepository`から常に
直接読み込む。キャッシュはIssue #15解消後、別PRで追加する。

## 影響範囲

- `prompt-engine-domain`: `domain.pipeline`（`PipelineContext`/`PipelineStage`/
  `PipelineMode`新設）、`domain.execution`（`ExecutionEngine`新設）、`domain.event`
  （`EventBusAdapter`新設）、`domain.audit`（`AuditRecord`/`AuditOutcome`/
  `AuditRepository`/`AuditFailureHandler`新設）、`domain.render`（`OutputDeclaration`
  新設）、`domain.prompt`（`PromptVersion`/`NewPromptVersion`/`PromptVersionMemento`に
  `output`追加）、`domain.composition`（`CompiledPrompt`に`output`追加）
- `prompt-engine-core`: `engine.execution.ExecutionCoordinator`が`ExecutionEngine`を実装、
  `engine.compiler.OutputFieldMapper`新設
- `prompt-engine-application`: 12ステージ実装（`engine.pipeline`ではなくCLAUDE.md
  パッケージルート直下`promptengine.application.pipeline`）、`PipelineFactory`、
  `PipelineOrchestrator`、`StageErrorMapper`新設（このモジュールへの初の実コード追加）
- `prompt-engine-infrastructure`: `InMemoryEventBusAdapter`、`InMemoryAuditRepository`、
  `Slf4jAuditFailureHandler`新設、`EventStorePromptRepository`に`output`列読み書き追加、
  マイグレーション`V6__output_declaration.sql`新設
- ADR-0014決定6を修正（第9節「決定の修正」参照）
- GitHub Issue #35（EventBusAdapter/AuditRepository本実装への置換）・#36
  （outputSchemaRef解決経路の設計）・#37（実DLQ実装）を作成
- 設計書§13.3（`RENDER_ERROR`行追加）、§12（ER図に`output`列追加）に本ADRの参照注記を追記

## 参照

- [PromptEngine_設計書.md §2.3 / §2.4 / §2.6 / §3.3 / §3.4 / §10 / §12 / §13.3 / §14 / §15.1 / §16](../PromptEngine_設計書.md)
- [実装ガイド §6.9](../PromptEngine_ClaudeCode実装ガイド.md)
- [ADR-0012: Validation Engine（`validation:`宣言の配線パターンの前例）](0012-validation-engine.md)
- [ADR-0014: Execution Adapter / Output Formatter（`ExecutionCoordinator`・決定6の修正対象）](0014-execution-response-parsing.md)
- GitHub Issue #32, #35, #36, #37
