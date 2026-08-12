# Prompt Engine（PE）

AIシステム全体で利用するPromptの作成・合成・検証・最適化・レンダリング・実行連携・評価・監査を一元管理する共通基盤です。
Promptをソースコードではなく、バージョン管理された「リソース」（Prompt as Resource / Prompt as Code）として扱い、
ライフサイクル（Draft → Review → Approved → Published → Deprecated → Archived）全体を管理します。

Promptがアプリケーションコード内に散在すると、変更にデプロイが必要になる、品質が属人化する、A/Bテストができない、
モデル変更時の影響範囲が不明になるといった問題が生じます。Prompt Engineはこれらを解決するため、Promptをコードから
分離し、ガバナンスの効いた共有資産として管理します。

## 関連基盤との責務分離

| 基盤 | 責務 | PEとの関係 |
|---|---|---|
| AACP | Agent実行・ワークフロー | Prompt Engine のクライアント |
| CIAP | 認証・認可 | PEは検証のみ行い、ユーザー管理は持たない |
| APAP | AIプロバイダ / モデル抽象化 | PEはモデルを直接呼ばない |
| PE（Prompt Engine） | Prompt資産の管理・テンプレート・評価・最適化 | 本リポジトリ |

## ドキュメント

- [設計書](docs/PromptEngine_設計書.md) — 唯一の仕様源
- [Claude Code 実装ガイド](docs/PromptEngine_ClaudeCode実装ガイド.md)
- [CLAUDE.md](CLAUDE.md) — 開発時の規約・作業の進め方
- [docs/adr/](docs/adr/) — アーキテクチャ決定記録（ADR）

## アーキテクチャ概要

Clean Architectureに基づくレイヤー構成（設計書§2.2、CLAUDE.md「モジュール依存の絶対規約」）。
依存の向きは下から上の一方向のみで、ArchUnit（`ArchitectureTest`）がCIで強制する。

```text
Interface Layer      prompt-engine-interface     REST API（Command/Query/Admin）、DTO、Security
        │  依存
Application Layer    prompt-engine-application   UseCase、Pipeline Orchestrator、トランザクション境界
        │  依存
Domain Layer         prompt-engine-domain        Aggregate・VO・Interface（フレームワーク非依存）
        ▲  実装
Engine / Infra       prompt-engine-core          DSL Parser/Compiler/Render Engine（domainのInterface実装）
                      prompt-engine-infrastructure Repository(JDBC)/Event Bus(Kafka)/Observability/Logging
Composition Root      prompt-engine-bootstrap     Spring Boot起動・DI配線（具象クラスの結線はここのみ）
Plugin                plugins/*                   ExecutionAdapter/OutputFormatter/TokenizerPlugin/ValidationRule実装
```

`prompt-engine-domain`はSpring/Jackson/JPA/SLF4Jを一切importしない。`prompt-engine-application`も
（トランザクション境界を除き）同様にフレームワーク非依存。詳細はCLAUDE.md参照。

## モジュール一覧

| モジュール | 責務 |
|---|---|
| `modules/prompt-engine-domain` | Prompt/Template/Fragment/Variable/Context/Evaluation等のAggregate・値オブジェクト・Repository/Port Interface |
| `modules/prompt-engine-application` | UseCase・Command/Query Handler・Pipeline Orchestrator・Event Publisher |
| `modules/prompt-engine-core`（Prompt Core） | DSL Parser/Compiler、Variable/Context Resolver、Validation/Optimization/Render Engine |
| `modules/prompt-engine-infrastructure` | JDBC Repository、Event Store/Outbox、Kafka連携、Observability（Metrics/Tracing/Logging）、Secretマスキング |
| `modules/prompt-engine-interface` | REST API（Command/Query/Admin）、DTO、Spring Security（CIAP JWT検証） |
| `modules/prompt-engine-plugin-api` | Plugin実装が参照する公開型（現状インターフェースのみ、実体はdomain側） |
| `modules/prompt-engine-bootstrap` | Spring Boot Composition Root。具象クラスのDI配線・`application.yml`・起動クラス |
| `modules/prompt-engine-testkit` | テスト共通ユーティリティ |
| `plugins/execution-fake` | `ExecutionAdapter`のFake実装（実APAP接続はM2） |
| `plugins/formatter-json` | JSON `OutputFormatter` |
| `plugins/tokenizer-approx` | 近似トークナイザ |
| `plugins/validator-policy` | ポリシー系`ValidationRule`の実装例 |
| `tests/integration` | Testcontainers統合テスト（Infrastructure層） |
| `tests/contract` | OpenAPI契約の静的検証 |
| `tests/prompt-regression` | Golden Prompt回帰テスト（renderHash決定性、後述） |

## セットアップ

### 必要なツール

- JDK 21（`./gradlew` の Java Toolchain 自動プロビジョニングを使う場合は不要。ネットワーク経由で自動取得される）
- Docker / Docker Compose（ローカル依存サービス起動用）

### 初期GitHub設定手順

新しくクローンしたリポジトリ、またはフォークで作業を始める場合の手順。

1. **ブランチ保護**: `main`ブランチは直接pushを禁止し、PR経由のみ許可する。必須ステータスチェックは
   `.github/workflows/ci.yml`（build/lint/arch-test/test）・`contract.yml`（contract）を設定する。
2. **CODEOWNERS**: `.github/CODEOWNERS`にレビュー必須者を設定する（既存ファイル参照）。
3. **Dependabot**: `.github/dependabot.yml`が既に設定済み（Gradle依存の自動更新PR）。
4. **Git hooksの有効化**（クローン後、最初に1回だけ）:

   ```bash
   git config core.hooksPath .githooks
   ```

   ステージ済みファイルに`.kt`が含まれるコミット時に`ktlintCheck`を自動実行するpre-commitフックが
   有効になる。

### ローカル開発の手順

```bash
docker compose up -d   # PostgreSQL 16 / Redis 7 / Redpanda（Kafka互換） / OpenSearch
```

`docker compose up -d`は**必須**（Dockerを使わないローカル実行手段は無い）。Flyway migrationが
起動時にPostgreSQLへ接続することを前提としており、`./gradlew :modules:prompt-engine-bootstrap:bootRun`
も`./gradlew integrationTest`もこのコンテナ群が起動済みであることを要求する。単体テスト（`./gradlew test`）
のみはDocker不要（Domain/Core/tests:prompt-regressionはモック無しの純粋な単体テスト、
Testcontainersを使うのは`tests/integration`と一部のbootstrap統合テストのみ）。

```bash
./gradlew build                 # 全モジュールビルド
./gradlew test                  # 単体テスト（Docker不要な範囲）
./gradlew integrationTest       # Testcontainers統合テスト
./gradlew ktlintCheck detekt    # 検証 + 静的解析（フォーマット自体を直すには ktlintFormat）
```

アプリケーションの起動:

```bash
./gradlew :modules:prompt-engine-bootstrap:bootRun
```

起動後、`http://localhost:8080/actuator/health` でヘルスチェックを確認できる
（Kubernetes向けの`/actuator/health/liveness`・`/actuator/health/readiness`も認証無しで到達可能）。
Swagger UIは `http://localhost:8080/swagger-ui.html`。

### コンテナイメージ

```bash
docker build -f deploy/docker/Dockerfile -t prompt-engine:local .
```

マルチステージビルド（Gradleビルド → Spring Boot jarmode toolsによるレイヤー抽出 →
`gcr.io/distroless/java21-debian12:nonroot`への配置）で、シェルを持たない非rootの最小イメージになる
（`deploy/docker/Dockerfile`のコメント参照）。

### Kubernetesデプロイ（Helm）

ローカル検証用（chart管理のSecretを使う。`secret.create`既定値`true`のまま）:

```bash
helm install prompt-engine deploy/helm/prompt-engine --set image.tag=<タグ>
```

本番運用（外部Secret Managerが供給済みのSecretを参照する。NFR-005）:

```bash
helm install prompt-engine deploy/helm/prompt-engine \
  --set image.tag=<タグ> \
  --set secret.create=false \
  --set secret.name=<既存Secret名>
```

`api` / `worker` / `admin` の3 Deploymentで構成する。3つとも同一イメージ（Admin API/BFFはM1未実装、
`deploy/helm/prompt-engine/templates/deployment-admin.yaml`のannotation参照）で、違いは
`PE_SCHEDULER_ENABLED`（Outbox Relay/Broker購読の背景ジョブ、`worker`のみ`true`）と
Serviceの公開範囲（`api`のみ外部Ingress対象を想定、`worker`/`admin`はClusterIPのみ）の2点のみ。
Secretは`values.yaml`の`secret.create=false`と`secret.name`で外部Secret Managerが供給する既存Secretを
参照する運用を想定する（チャート自体はSecret値をテンプレートしない設計）。**既定の`secret.create=true`は
ローカル検証専用であり、本番では必ず`false`にして上記の手順を使うこと。**

## 性能測定（NFR-002 / NFR-003）

### NFR-002（Prompt取得キャッシュヒット p99≤20ms）: M1では未検証

`PromptCache`（設計書§16拡張ポイント#9）がM1で未実装のため測定不能。詳細は
「NFR-001〜NFR-010 検証状況」・[ADR-0028](docs/adr/0028-p11-finalize.md)・Issue #77を参照。

### NFR-003（Render p99≤200ms）: 検証済 — 目標達成

`tools/perf/render_load_test.sh`で実測した。**再現手順**:

```bash
./tools/perf/render_load_test.sh
```

（`docker` / `docker compose` / `curl` / `pg_isready` / JDK21の`java`が必要。イメージビルド →
`compose.yaml`のPostgreSQL起動 → `tools/perf/DevJwks.java`によるローカル限定JWKS+JWT発行 →
対象コンテナ起動 → `RenderLoadSeeder`によるPublished Prompt Version作成 → ウォームアップ →
測定 → 後片付け、まで一括で行う）

**測定条件**:

| 項目 | 値 |
|---|---|
| 対象イメージ | `deploy/docker/Dockerfile`でビルドした`prompt-engine:p11-perf`（distroless nonroot） |
| リソース制限 | `docker run --cpus 1 --memory 1g`（Helm Chart既定の`resources.limits`と同一値） |
| 測定対象データ | `tests/prompt-regression/fixtures/valid/04-production-scale-support-agent.prompt`（本番相当サイズ: 長いsystemブロック＋few-shot例4組） |
| 認証 | `tools/perf/DevJwks.java`が発行するローカル限定の使い捨てJWT（`pe.ciap.jwks-uri`を差し替え） |
| ウォームアップ | 5,000リクエスト（単一接続・逐次） |
| 測定 | 2,000リクエスト（並列クライアント数10、各クライアントは200リクエストを自分の接続で使い回す） |
| 測定日時 | 2026-08-11（ローカル開発機、macOS、Docker Desktop） |

**実測結果**（2000/2000がHTTP 200、失敗なし）:

| 指標 | 値 |
|---|---|
| p50 | 5.47ms |
| p99 | **80.03ms**（目標 ≤200ms、達成） |
| max | 173.94ms |
| ウォームアップ末尾200件平均 | 1.79ms（単一接続・並列度1） |
| 測定先頭200件平均 | 17.16ms（並列度10） |

**測定方法の限界（正直に記録する）**:

- **ウォームアップ末尾平均（1.79ms）と測定先頭平均（17.16ms）を突き合わせて「ウォームアップは
  十分か」を判定する、というこのスクリプトの当初のもくろみ自体が成立していない。**
  ウォームアップは並列度1・測定は並列度10で行っており、条件そのものが異なるため
  （CPU 1個の制限下では並列度が上がるほどキューイング/コンテンションの影響を受けやすくなる、
  という一般的な傾向はあるが、本測定はそれを切り分けて検証してはいない）、この2値の乖離を
  「JITが未安定」の根拠にも「安定している」の根拠にも使えない。同様に、p50（5.47ms）に対し
  p99（80.03ms）・max（173.94ms）が大きく右に裾を引く分布についても、GCや並列コンテンションを
  原因と断定できる追加計測（GCログ、スレッドダンプ等）は取得していない ── あくまで一つの
  仮説であり、確認できた事実は「測定2,000件が100%成功し、目標の200msを一度も超えなかった」
  という点のみである。
  ウォームアップ十分性を厳密に判定したい場合は、測定と同じ並列度でウォームアップ末尾を
  比較する手順に変更する必要があり、本フェーズでは未対応（今後の改善余地）。
- ローカル開発機での単発実測であり、CI環境・本番環境のリソース制約やネットワーク条件とは
  異なる。CI回帰ゲート化は本フェーズのスコープ外（必要になった時点で別Issueを起こす）。

詳細な決定の経緯は[ADR-0028](docs/adr/0028-p11-finalize.md)を参照。

## FR-001〜FR-024 実装状況

設計書§1.8の機能要件表と現在の実装を突き合わせた結果（コード確認済み、推測なし）。

| ID | 状態 | 根拠 | 備考 |
|---|---|---|---|
| FR-001 | 部分実装 | `PromptRepository.kt`（CRUD）、`PromptController.kt`（DELETE→archive論理削除） | CRUD・論理削除は実装済。「複製」に相当する`duplicate`/`clone`/`fork`は未実装。Issueなし |
| FR-002 | 実装済 | `PromptVersion.kt`（Immutable）、`SemVer.kt`、`PromptVersionDiff.kt`、`Prompt.kt`（rollback）、`VersionController.kt`（diff/rollbackエンドポイント） | Version生成・SemVer・Diff・Rollbackを確認 |
| FR-003 | 実装済 | `LifecycleState.kt`（Draft/InReview/Approved/Published/Deprecated/Archivedの6状態、State継承） | 遷移制約込みで確認 |
| FR-004 | 実装済 | `Template.kt`、`TemplateVersion.kt`、`ExtendsRef.kt`、`VariableDefinition.kt` | パラメータ定義・extends継承・デフォルト値を確認 |
| FR-005 | 実装済 | `Fragment.kt`、`FragmentResolver.kt`、`IncludeTargetResolver.kt`（`{{> alias\|fragmentKey }}`構文） | Include/Import参照を確認 |
| FR-006 | 部分実装 | `ReferenceResolver.kt`（循環検出）、`MacroExpander.kt`、`IncludeTargetResolver.kt`（`NestedPromptNotSupportedException`を明示的にthrow） | Include/Import/Macro/Inheritance/循環検出は実装済。Nested Prompt（`{{> prompt:key }}`）は明示的に未対応。Issue #19（M2） |
| FR-007 | 実装済 | `VariableSource.kt`（6種enum）、`engine/resolver/`配下の各Resolver（Explicit/Static/User/Workflow/Environment/Secret） | 6種すべて確認 |
| FR-008 | 実装済 | `ContextResolverImpl.kt`（`MERGE_ORDER`に7 scope）、`StandardContextResolver.kt` | environment/system/application/workflow/user/memory/conversationの7 scopeを確認 |
| FR-009 | 実装済 | `engine/validation/`配下の5 Rule、`plugins/validator-policy`（Rule Plugin追加の実例） | 標準ルール＋Plugin拡張を確認 |
| FR-010 | 部分実装 | `CompressionRule.kt`、`TokenOptimizationRule.kt`、`ExpansionRule.kt`、`ModelProfile.kt` | Token最適化/Compression/Model Profile別最適化は実装。設計書§2.11のFew-shot例注入は未実装（`ExpansionRule.kt`のKDocで明記）。Issue #29（M2） |
| FR-011 | 実装済 | `TemplateEngine.kt`（差替可能Interface）、`DefaultTemplateEngine.kt` | Template Engine差替・決定的Render契約を確認 |
| FR-012 | 部分実装 | `OutputFormatter.kt`（対応`OutputFormat`はJSON/XML/MARKDOWN/TEXT）、`TextOutputFormatter.kt`、`plugins/formatter-json` | 実装済Formatterは TEXT/JSON の2種のみ。XML/Markdown/Structured Output実装クラスは無し。Issueなし |
| FR-013 | 部分実装 | `ExecutionAdapter.kt`（実APAP接続はM2と明記）、`RetryingExecutionAdapter.kt`、`plugins/execution-fake`、`ProductionProfileGuardTest.kt`（production起動時Fakeガード） | Provider非依存Interface＋リトライ＋Fakeは実装済。実APAP Adapterは未実装。Issue #31（M2） |
| FR-014 | 部分実装 | `EvaluationRule.kt`（M1は実行系3種のみと明記）、`LatencyEvaluationRule.kt`/`TokenUsageEvaluationRule.kt`/`CostEvaluationRule.kt` | Latency/Token/Costのみ実装。Quality/Consistency/Determinismは拡張点定義のみ。Issueなし（Plugin追加前提の設計） |
| FR-015 | 未実装 | （該当実装なし） | README「M1の非対象」の通り。M2milestoneで対応予定 |
| FR-016 | 部分実装 | `Prompt.kt`（submitForReview/reject/withdraw/approveは生メソッドとして存在）、`docs/adr/0016-review-endpoints-deferred-to-m2.md` | 状態遷移メソッド自体はAggregate内にあるが、対応HTTPエンドポイントは意図的に非公開。ReviewCase Aggregate（4-eyes原則・監査発行の主体）が未実装のため、この経路の遷移は監査ログに記録されない。Issue #9（M2） |
| FR-017 | 部分実装 | `JdbcPromptSearchRepository.kt`（Tag/Category/Status絞り込みは構造化SQL、全文検索は`ILIKE`部分一致のみ） | Fallbackのみ（README記載通り）。実FTS（tsvector/GIN等）は未実装。Issueなし |
| FR-018 | 実装済 | `DependencyRepository.kt`（findOutbound/findInbound）、`DependencyController.kt`、`ReferenceResolver.kt`（循環検出） | 依存グラフ・影響分析・循環検出を確認 |
| FR-019 | 部分実装 | `PromptDtos.kt`/`VersionController.kt`（単一Prompt単位のDSLテキスト入出力） | 単一Prompt単位の入出力は実装済。複数リソースをまとめた「バンドル」入出力は未実装。Issueなし |
| FR-020 | 実装済 | `AuditRepository.kt`（append/record、update/delete非提供）、`AuditStage.kt`（Pipeline Stage12）、`V1__init.sql`（audit_logsテーブル） | 全変更・全実行の監査記録経路を確認 |
| FR-021 | 実装済 | `MetricsRecorder.kt`、`MicrometerMetricsRecorder.kt`、`OpenTelemetryPipelineTracer.kt` | Token/Cost/Latency/成功率＋分散Tracingを確認 |
| FR-022 | 部分実装 | `PromptCacheInvalidator.kt`（永続キャッシュ実装は存在しないと明記）、`InMemoryPromptCacheInvalidator.kt`（無効化ログのみの最小実装） | 無効化の「配線」のみ実装、実キャッシュは無い。Issue #77（Issue #15解消待ち、M2） |
| FR-023 | 部分実装 | `PromptDomainEvent.kt`（Prompt Aggregateは発行済）、`Template.kt`/`Fragment.kt`（publish/archiveがイベント非発行） | Prompt Aggregateのみイベント化済み。Template/Fragmentは未発行。Issue #15（M2） |
| FR-024 | 部分実装 | 設計書§16（14拡張ポイント定義）、`PluginEngineConfig.kt`（静的Spring `@Bean`配線）、`prompt-engine-plugin-api`（実体クラス0件） | 拡張ポイントInterfaceと4種のPlugin実装は存在するが、Plugin Manifest宣言・実行時登録/活性化/障害隔離・再起動不要追加は未実装（コンパイル時静的DIのみ）。Issueなし |

## NFR-001〜NFR-010 検証状況

| ID | 状態 | 検証方法・根拠 | 備考 |
|---|---|---|---|
| NFR-001 | 未検証 | `deploy/helm/prompt-engine/templates/hpa.yaml`（水平スケール）、`PromptCacheInvalidator.kt`（Read縮退継続の前提となるCacheが未実装） | 稼働率自体は運用開始前で測定不能。「Read系はキャッシュで縮退継続」の前提となる`PromptCache`実装が無いため縮退継続の仕組み自体が未実装。Issue #77関連（M2） |
| NFR-002 | 未検証 | 設計書§1.9 NFR-002行に注記済み | `PromptCache`未実装のため測定不能。Issue #77（Issue #15解消待ち、M2） |
| NFR-003 | 検証済（p99=80.03ms、目標≤200ms達成） | `tools/perf/render_load_test.sh`（実コンテナ、CPU1/メモリ1Gi制限、2000リクエスト） | 実測条件・結果は上記「性能測定」節に記録 |
| NFR-004 | 部分実装/未検証 | `deploy/helm/prompt-engine/templates/hpa.yaml`（HPA、CPU使用率ベース）、`PluginEngineConfig.kt`（静的Spring `@Bean`配線） | 水平スケール（ステートレスAPI+HPA）は実装済。「Plugin追加は再起動不要」は未達成 — Pluginは`plugins/`配下のGradleサブプロジェクトとしてコンパイル時に静的リンクされ、動的ロード機構が無いため追加には再ビルド・再起動が必須 |
| NFR-005 | 検証済 | `SecurityConfig.kt`（OAuth2 Resource Server + JWT検証）、各Controllerの`@PreAuthorize`（RBAC+スコープ）、`SecretManagerAdapter.kt`（Secret参照のみ保持）、`SanitizingJsonEncoder.kt`（3層防御のログマスキング） | CIAP連携・RBAC・Secret参照のみ保持・ログマスキングをコードで確認 |
| NFR-006 | 部分実装/未検証 | `AuditRepository.kt`（update/delete非提供のInterface）、`V1__init.sql`（追記専用はコメントの運用前提のみ、実GRANT/REVOKE文なし） | 追記専用制約はアプリケーション層のみで担保、DB層での強制は未実装。保持期間設定（既定7年）に対応する設定・パージ処理も未実装。Issueなし |
| NFR-007 | 検証済 | `ArchitectureTest.kt`（ArchUnitルール一式: domain非依存、application→domainのみ、core/infrastructure→application禁止、interfaces→application限定、Plugin実装の参照制限） | CIの`arch-test`ジョブで常時検証 |
| NFR-008 | 検証済 | `OpenTelemetryPipelineTracer.kt`（実Trace実装）、`MicrometerMetricsRecorder.kt`（Metrics、`/actuator/prometheus`）、`SanitizingJsonEncoder.kt`（構造化JSON Log） | OTel互換のTrace/Metrics/構造化Logの3系統を確認 |
| NFR-009 | 未検証 | `OutboxRelayScheduler.kt`/`SubscriberScheduler.kt`（既定750ms/500msポーリング、目標の5s以内と整合する短周期） | ポーリング間隔の設計値は目標と整合するが、エンドツーエンドの反映遅延を実測・アサートするテストは無い |
| NFR-010 | 検証済 | `.github/workflows/contract.yml`（PRごとにoasdiffで`api/openapi.yaml`の破壊的変更を検出、`fail-on: ERR`） | CIの`contract`ジョブが機械的に検証 |

## M1完了後に持ち越す既知の未実装・未検証（Issue対応表）

P11時点でOpenのIssue一覧（すべてM2またはM1完了後milestoneに紐づけ済み。裸の"M1"milestoneで
Openのままの項目は無い）。

| Issue | Milestone | 内容 | 関連FR/NFR |
|---|---|---|---|
| [#9](https://github.com/io0323/prompt-engine/issues/9) | M2 | ReviewCase Aggregateの実装、レビュー系イベントの監査欠落解消 | FR-016 |
| [#15](https://github.com/io0323/prompt-engine/issues/15) | M2 | Template/Fragmentの Domain Event追加 | FR-023 |
| [#18](https://github.com/io0323/prompt-engine/issues/18) | M1完了後 | Repository.save()の全Version毎回rewrite問題 | — |
| [#19](https://github.com/io0323/prompt-engine/issues/19) | M2 | Nested Prompt（`{{> prompt:key }}`）の実装 | FR-006 |
| [#29](https://github.com/io0323/prompt-engine/issues/29) | M2 | ExpansionRuleへのFew-shot例注入 | FR-010 |
| [#31](https://github.com/io0323/prompt-engine/issues/31) | M2 | APAP統合時のリトライ責務重複解消 | FR-013 |
| [#52](https://github.com/io0323/prompt-engine/issues/52) | M2 | SchemaRepository新設・outputSchemaRef解決 | — |
| [#75](https://github.com/io0323/prompt-engine/issues/75) | M1完了後 | ログ相関IDへpromptKey/version追加 | — |
| [#77](https://github.com/io0323/prompt-engine/issues/77) | M2 | PromptCache実装（NFR-002検証可能化） | FR-022 / NFR-001 / NFR-002 |
| [#78](https://github.com/io0323/prompt-engine/issues/78) | M2 | Promptの複製（duplicate/clone） | FR-001 |
| [#79](https://github.com/io0323/prompt-engine/issues/79) | M2 | XML/Markdown/Structured Output Formatter | FR-012 |
| [#80](https://github.com/io0323/prompt-engine/issues/80) | M2 | Prompt/Response Quality・Consistency・Determinism評価Rule | FR-014 |
| [#81](https://github.com/io0323/prompt-engine/issues/81) | M2 | バンドルのImport/Export | FR-019 |
| [#82](https://github.com/io0323/prompt-engine/issues/82) | M2 | Plugin Manager（実行時登録・再起動不要） | FR-024 / NFR-004 |

（Issue #11・#35・#37・#38・#48・#50はP10a/b/cで実装済みだったがクローズ漏れていたため、
P11の棚卸しでクローズ済み。）

## Golden Prompt回帰テスト

`tests/prompt-regression`が、サンプル`.prompt`一式（`fixtures/valid/`）のParse→Compile→Renderを
DBもTestcontainersも使わずin-memoryで行い、決定的な`renderHash`（FR-011）を`golden/<fixture名>.hash`と
比較する。golden fixtureが0件の場合はテスト自体を失敗させる（`fixtures.shouldNotBeEmpty()`）。

意図的な変更でrenderHashが変わった場合のgolden更新手順:

```bash
RENDER_REGRESSION_UPDATE_GOLDEN=1 ./gradlew :tests:prompt-regression:test
git diff tests/prompt-regression/golden/   # 意図した変更かを確認してからコミットする
```

## 実装フェーズ一覧（M1、完了）

各フェーズは1PR単位。

| Phase | 内容 | 主要成果物 |
|---|---|---|
| P0 | リポジトリ初期化 | Gradle骨格、CLAUDE.md、CI、ArchUnit規約、compose.yaml |
| P1 | ドメインモデル | Prompt / PromptVersion / VO / LifecycleState / Domain Event |
| P2 | 永続化 + Event Store | Flyway migration、Repository実装、Outbox |
| P3 | DSL Parser / Compiler | `.prompt` パース、AST、extends/import/include/macro、循環検出 |
| P4 | Resolver（Variable / Context） | ResolverChain、6種Variable、7種Context、Secretマスク |
| P5 | Validation Engine | 6 Rule + Report集約 + severity |
| P6 | Optimization + Render | Tokenizer Plugin、最適化Rule、RenderedPrompt、renderHash |
| P7 | Execution + Parsing | ExecutionAdapter（Fake）、OutputFormatter（JSON/Text）、修復リトライ |
| P8 | Pipeline Orchestrator | 12ステージ結線、Stage別Span、エラーコード写像 |
| P9 | REST API + 認可 | 設計書§13の主要エンドポイント、エラー形式、OpenAPI突合 |
| P10 | Evaluation + Audit + Monitoring | 非同期評価Subscriber、Audit追記、Metrics/Trace |
| P11 | 仕上げ | Helm/Dockerfile、README、prompt-regression、負荷確認 |

**M1の非対象**（M2で対応）: Experiment Engine（FR-015）、Search Engine本実装（FR-017、現状Fallbackのみ）、
Review/Approvalワークフロー（FR-016、ReviewCase Aggregate、Issue #9）、実APAP接続（FR-013、Issue #31）、
Template/Fragment Domain Event（FR-023、Issue #15）、PromptCache（FR-022/NFR-001/NFR-002、Issue #77）、
Nested Prompt（FR-006、Issue #19）。
