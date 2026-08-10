# ADR-0027: Monitoring（Metrics/Tracing/Logging）とIN_PROGRESS滞留対策（P10c）

## ステータス

Accepted

## コンテキスト

設計書§2.15（Monitoring仕様）はMetrics/Tracing/Logging/Alertの4項目を列挙するのみで、
ラベル設計・エクスポータ構成・ログのSecretマスクの強制方法までは規定していない。
P8で`PipelineTracer`（Span生成の抽象）を導入したが実装は`NoopPipelineTracer`のままで
あり（Issue #38）、Micrometerメトリクス・構造化ログは本フェーズ（P10c、
`docs/prompts/p10c.md`）まで一切未着手だった。

また、P9bで導入した`IdempotentCommandExecutor.executeLongRunning`は、予約
（`idempotency_keys.status='IN_PROGRESS'`）後にプロセスがクラッシュすると予約が
永久に残り、同一Idempotency-Keyへの以降の全リクエストが`IdempotencyKeyInProgressException`
（409）で永久にブロックされる既知の制約を持っていた（Issue #50）。

本ADRはP10cのスコープとして、事前の方針提示・承認を経て確定した以下4点を記録する。

## 決定

### 1. メトリクスのラベル設計（カーディナリティ）

`promptKey`/`version`/`traceId`はいかなるMicrometerメトリクスにもラベルとして
付けない。`domain.observability.MetricsRecorder`（`prompt-engine-domain`）の
メソッドシグネチャ自体が、有界な型（`PipelineMode`/`Outcome`/`TokenDirection`/
`Severity`/`ExecutionErrorType`）または設計上有界と明記した`String`
（`stage`＝Pipeline構成が固定する12種、`ruleId`＝導入Pluginの数だけの固定集合）
のみを受け取り、`promptKey`等を渡す経路自体を型で塞ぐ。

| メトリクス | ラベル | データソース |
|---|---|---|
| `pipeline_stage_duration_seconds` | `stage`, `mode` | `PipelineOrchestrator` |
| `pipeline_render_duration_seconds` | `mode`, `outcome` | `PipelineOrchestrator`（Stage1〜8合計、NFR-003監視用） |
| `render_count_total` | `outcome` | `PipelineOrchestrator` |
| `validation_failure_count_total` | `ruleId`, `severity` | `ValidationStage`（`ValidationReport.findings`を直接持つ唯一の箇所） |
| `token_usage_total` | `direction` | `PipelineOrchestrator`（`executionOutcome.attempts`全件の合算、解析修復分を含む） |
| `cost_total` | なし | `PipelineOrchestrator`（同上、合算トークン数×`costPerToken`） |
| `execution_attempts_total` | `outcome`, `errorType` | `PipelineOrchestrator`（Stage 9呼出1回につき1件。`execution_success_rate`の実体） |
| `cache_hit_ratio` | 未実装 | PromptCache本体が無い（設計書§2.14未実装）ため計装対象が無い |
| `experiment_variant_count` | 未実装 | Experiment Engineが無いため計装対象が無い |

Prompt単位のコスト・トークン分析は`execution_logs`のクエリ（`JdbcExecutionLogRepository`が
持つ`execution_logs JOIN prompt_versions JOIN prompts`のJOINパターン）で行う。
`execution_success_rate`はアプリ内で比率を保持せず、`execution_attempts_total{outcome=...}`
の2カウンタとして公開し、比率計算はPromQL等の監視基盤側に委ねる。

`MicrometerMetricsRecorderCardinalityTest`（`prompt-engine-infrastructure`）が、
実際に記録される全メトリクスの全タグキーを許可リスト（`stage`/`mode`/`outcome`/
`ruleId`/`severity`/`direction`/`errorType`）と突き合わせる回帰テストとして機能する。

NFR-002（キャッシュヒット時のPrompt取得、p99≤20ms）はPromptCache本体が無いため
本フェーズでは監視対象にできない。Cache実装フェーズで`pipeline_render_duration_seconds`
と同じ発想のTimerを追加する前提を書き残す（既知のギャップ）。

### 2. OTelエクスポータの構成

- **Metrics（Pull型）**: `micrometer-registry-prometheus`を追加し、
  `management.endpoints.web.exposure.include`へ`prometheus`を加えて
  `/actuator/prometheus`を公開する。送信先設定が不要なため、ローカル・CIでの
  起動失敗・テスト遅延が構造的に発生しない。
- **Tracing（Push型、OTLP）**: `promptengine.observability.otel.exporter-endpoint`
  （環境変数`PE_OTEL_EXPORTER_ENDPOINT`）が空なら、`OtelTracerConfig`は
  `SdkTracerProvider`に`SpanProcessor`を一切登録しない。Spanオブジェクト自体は
  生成されるが処理系が無いため即座に破棄されるだけで、ネットワーク呼出も起動遅延も
  発生しない（`OpenTelemetry.noop()`へ分岐するより、常に同じ`SdkTracerProvider`の
  コードパスを通す方が環境間でテスト対象を揃えられるためこの実装を選んだ）。
  設定されていれば`OtlpGrpcSpanExporter` + `BatchSpanProcessor`（非同期）を構成する。
- **本番未設定時の扱い**: `InMemoryAuditRepository`等の「productionプロファイルでは
  起動失敗」パターン（ADR-0015決定7）とは揃えない。Traceは診断用途であり、
  `AuditRepository`（NFR-006、コンプライアンス要件）とは重みが異なるため、
  `production`プロファイルでエンドポイント未設定の場合は起動時にWARNログを
  出すに留め、起動は失敗させない。

### 3. LogSanitizerの強制方法

ログ基盤自体が本フェーズまで存在しなかったため、最初から「サニタイズを経由しないと
出力できない」構造で構築した。

- `logback-spring.xml`（`prompt-engine-bootstrap`）は全プロファイル共通で
  `SanitizingJsonEncoder`のみを使う1系統のAppenderしか定義しない。個別クラスが
  独自にオプトインできる第二のログ出力経路を作らない。
- `SanitizingJsonEncoder`（`prompt-engine-infrastructure`）は、フォーマット済みJSONに
  対して既存の`SecretMaskingJsonSanitizer`（P10b、ADR-0026決定4。フィールド名
  サフィックスマッチで`secret`/`password`/`token`等をマスクしつつ`inputTokens`等の
  正当なフィールドを誤マスクしない教訓込み）を通してからバイト列を書き出す。
  ロガー呼出側（`logger.info(...)`）は一切関与できない単一入口であり、
  P7の`RenderHashCalculator`（呼び忘れようがない単一入口）と同じ構造をログ出口へ
  適用したもの。呼出規約（「経由すること」という約束）ではなく、Encoder層という
  構造でSecretマスクを担保する。
- `SensitiveValue`（型レベルマスク、既存）がログ出力の手前で値そのものを`"***"`化する
  第1層、`SecretMaskingJsonSanitizer`をEncoderへ組み込むのが第2層（フィールド名で
  拾うdefense-in-depth）という、P10bで`AuditEngine`に確立済みの二層構成を
  ログ出口全体へ広げた。
- 相関ID: `TraceIdFilter`（P9c、既存）を拡張し、リクエスト単位で
  `MDC.put("traceId", ...)`／`finally`での`MDC.remove`を行う。`SanitizingJsonEncoder`は
  受け取ったMDCをそのままJSONフィールドへ展開するのみで、MDC投入経路とログ出力経路を
  分離する。
- **promptKey/versionのMDC投入は本フェーズでは行わない**。`prompt-engine-application`
  はCLAUDE.mdのArchUnit規約でSLF4Jへの依存を禁止されているため、`PipelineOrchestrator`
  から直接MDCを操作できない。`PipelineTracer.withSpan`（domain）のシグネチャは
  `stageName`/`traceId`のみで`PipelineContext`全体を受け取らないため、
  `OpenTelemetryPipelineTracer`側で補うこともできない。promptKey単位の相関は
  traceId経由で`audit_logs`/`execution_logs`（いずれも`trace_id`列とPromptを
  紐づけて保持）を突き合わせれば得られるため、既存のtraceId相関で実用上の必要は
  満たされると判断し、`PipelineTracer`/`MetricsRecorder`と同型の新しい抽象を
  ログ専用に追加するコストには見合わないと判断した（既知のスコープ限定）。

### 4. IN_PROGRESS滞留対策（Issue #50）

ADR-0025のOutbox中継が使う「クレーム所有トークン＋タイムアウトベースの期限切れ判定＋
書き戻し時のフェンシング」という3段階Claim方式（同ADR §3）と同じ構造で解決した。
トリガー機構のみが異なる: Outboxは背景ポーラーがキューを能動的に排出する必要が
あるためタイムアウト監視も定期実行するが、`idempotency_keys`は「同一キーでの再送」が
起きたときにのみ意味を持ち排出すべきキューが無いため、バックグラウンドスイーパーは
追加しない。代わりに、同一Idempotency-Keyへの次回リクエストが
`JdbcIdempotentCommandExecutor.reserveOrResolve`内でinlineに期限切れ予約を奪取する
（steal-on-retry）。

- マイグレーション`V14__idempotency_keys_claim_columns.sql`: `idempotency_keys`へ
  `claimed_at`/`claimed_by`を追加。マイグレーション適用以前から`IN_PROGRESS`のまま
  滞留していた行（＝Issue #50が指す状況そのもの）は`claimed_at = created_at`
  （`now()`ではなく本来の予約時刻）へバックフィルし、適用直後から期限切れ判定の
  対象になるようにした。
- `claimTimeoutSeconds`（既定120秒、`IdempotencyClaimProperties`、
  `promptengine.idempotency.claim-timeout-seconds`）を超えて`IN_PROGRESS`のままの
  予約は期限切れとみなし、`claimed_by`が一致する行にのみ作用する条件付きUPDATEで
  奪取する。0件更新（既に他リクエストが奪取済み）ならループ/リトライせず
  `IdempotencyKeyInProgressException`にフォールバックする（クライアント自身の
  再送で解消される、単純で安全なスプリアス409）。
- 所有トークンは1回の`executeLongRunning`呼出ごとに新規`UUID`を発行する
  （Outboxの`instanceId`のようなBeanレベル共有トークンは、1呼出のcall stack内で
  完結するこの用途には不要）。完了記録（`markCompleted`）・解放（`releaseReservation`）
  は所有トークンが一致する行にのみ作用するフェンシング条件を課す。フェンシングに
  敗れた場合（他リクエストが期限切れ後に奪取済み）は例外にせずWARNログ
  （`idempotency_fencing_lost`、Outboxの`outbox_relay_fencing_lost`と同じ命名規則）を
  出すのみとし、`operation()`が実際に成功していれば呼出元へは成功として返す
  （正準の完了記録は奪取した側が別途担う）。
- `executeInTransaction`（CRUD系、単一コミットトランザクション）は、自分自身が
  クラッシュで`IN_PROGRESS`行を残すことは無い（予約INSERTから完了UPDATEまでが常に
  1つの未コミットトランザクション内で完結するため、プロセスクラッシュ時はPostgres
  自体がロールバックする）。ただし共有の`reserveOrResolve`を経由するため、
  `executeLongRunning`が残した期限切れ行を奪取する側には回りうる。

**既知の限界（CodeRabbitレビュー指摘）**: この3段階Claim方式が保証するのは
「予約の所有権の一貫性」（`markCompleted`/`releaseReservation`が正しい行にのみ
作用する）であり、「奪取された側の`operation`が実際に停止すること」ではない。
`claimTimeoutSeconds`はあくまで「クラッシュしたとみなす」ヒューリスティックであり、
元のプロセスが実際には生きていて（GCポーズ・一時的なネットワーク分断等）
`operation`（典型的にはAPAP実行のような非冪等な外部副作用）の実行を継続している
場合、奪取後に別リクエストが同じ`operation`を再実行すると、外部副作用が二重に
発生しうる。Outboxの3段階Claim（ADR-0025）も構造的に同じ限界を持つが、Outbox側は
配信そのものが少なくとも1回配信（at-least-once）を前提とし受信側の冪等性
（`event_id`によるUPSERT/ON CONFLICT DO NOTHING）で吸収する設計のため実害が無い。
`IdempotentCommandExecutor.executeLongRunning`を使う`operation`（将来のAPAP実行等）も、
再実行されても安全（冪等、またはAPAP側が独自の冪等キーで重複排除する）であることを
呼出側の契約とする。非冪等な`operation`をこの経路に載せる場合は、本ADRのこの限界を
踏まえた別途の設計判断が必要である。

## 影響範囲

- `prompt-engine-domain`: `domain.observability.MetricsRecorder`/`Outcome`/
  `TokenDirection`/`TraceContextPropagator`（新設）
- `prompt-engine-application`: `PipelineOrchestrator`/`ValidationStage`が
  `MetricsRecorder`を受け取るようコンストラクタ変更
- `prompt-engine-infrastructure`: `MicrometerMetricsRecorder`/
  `OpenTelemetryPipelineTracer`/`OpenTelemetryTraceContextPropagator`/
  `SanitizingJsonEncoder`（新設）、`JdbcIdempotentCommandExecutor`（クレーム奪取・
  フェンシング追加）
- `prompt-engine-bootstrap`: `MetricsConfig`/`OtelTracerConfig`/
  `OtelTracerProperties`/`IdempotencyClaimProperties`（新設）、
  `PipelineConfig`/`AuditEventConfig`/`PluginEngineConfig`の配線変更、
  `logback-spring.xml`（新設）、`application.yml`更新
- `prompt-engine-interface`: `TraceIdFilter`がMDCへ`traceId`を投入
- マイグレーション: `V14__idempotency_keys_claim_columns.sql`
- 設計書§2.15のLogging行「相関ID（traceId/promptKey/version）」は、P10c時点で
  `traceId`のみ実装（`promptKey`/`version`は未実装のまま）である旨を明記する形で
  更新した（CodeRabbitレビュー指摘: 当初「大きな改訂は不要」としていたが、
  §2.15の記述と実装状況の間に食い違いがあり、読者が誤解しうる状態だった）。

## 参照

- [ADR-0025: Event Bus実装（Outbox → Broker中継）とTopic routing（クレーム機構の原型）](0025-event-bus-outbox-relay.md)
- [ADR-0026: Evaluation/Audit Subscribers/DLQ（SecretMaskingJsonSanitizerの初出、二層防御構成）](0026-evaluation-audit-subscribers-dlq.md)
- [PromptEngine_設計書.md §2.15 / §1.9](../PromptEngine_設計書.md)
- [Issue #38: OpenTelemetry Tracer実装](https://github.com/io0323/prompt-engine/issues/38)
- [Issue #50: IN_PROGRESS予約のクラッシュ後滞留](https://github.com/io0323/prompt-engine/issues/50)
