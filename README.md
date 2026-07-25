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

## 現在のステータス

P0（リポジトリ初期化）着手中。Gradleマルチモジュールの骨格を構築済み。

## セットアップ

### 必要なツール

- JDK 21（`./gradlew` の Java Toolchain 自動プロビジョニングを使う場合は不要。ネットワーク経由で自動取得される）
- Docker / Docker Compose（ローカル依存サービス起動用）

### Git hooksの有効化

クローン後、最初に1回だけ実行してください。ステージ済みファイルに `.kt` が含まれる
コミット時に `ktlintCheck` を自動実行する pre-commit フックが有効になります。

```bash
git config core.hooksPath .githooks
```

### ローカル依存サービスの起動

```bash
docker compose up -d   # PostgreSQL 16 / Redis 7 / Redpanda / OpenSearch
```

### ビルド・テスト

```bash
./gradlew build                 # 全モジュールビルド
./gradlew test                  # 単体テスト
./gradlew integrationTest       # Testcontainers統合テスト（P2以降）
./gradlew ktlintFormat detekt   # フォーマット + 静的解析
```

### アプリケーションの起動

```bash
./gradlew :modules:prompt-engine-bootstrap:bootRun
```

起動後、`http://localhost:8080/actuator/health` でヘルスチェックを確認できます。

## 実装フェーズ一覧（M1）

各フェーズは1PR単位。前フェーズがマージ済であることを前提とします。

| Phase | 内容 | 主要成果物 | 完了条件 |
|---|---|---|---|
| P0 | リポジトリ初期化 | Gradle骨格、CLAUDE.md、CI、ArchUnit規約、compose.yaml | `./gradlew build` 成功、CI green |
| P1 | ドメインモデル | Prompt / PromptVersion / VO / LifecycleState / Domain Event | 状態遷移の単体テスト全通過（設計書§2.5の表を網羅） |
| P2 | 永続化 + Event Store | Flyway migration、Repository実装、Outbox | Testcontainersで保存・復元・イベント追記が通る |
| P3 | DSL Parser / Compiler | `.prompt` パース、AST、extends/import/include/macro、循環検出 | `docs/dsl/samples/*.prompt` が全てCompile成功。異常系テスト完備 |
| P4 | Resolver（Variable / Context） | ResolverChain、6種Variable、7種Context、Secretマスク | 優先順位・未解決エラー・マスクのテスト通過 |
| P5 | Validation Engine | 6 Rule + Report集約 + severity | 各Ruleの正常/異常テスト、DSL宣言（maxTokens等）反映 |
| P6 | Optimization + Render | Tokenizer Plugin、最適化Rule、RenderedPrompt、renderHash | 同一入力→バイト同一出力（決定性テスト） |
| P7 | Execution + Parsing | ExecutionAdapter（Fake）、OutputFormatter（JSON/Text）、修復リトライ | Fakeでexecute E2E成功、parse失敗時の挙動テスト |
| P8 | Pipeline Orchestrator | 12ステージ結線、Stage別Span、エラーコード写像 | RENDER_ONLY / FULL_EXECUTION / COMPILE_ONLY の3モードE2E |
| P9 | REST API + 認可 | 設計書§13の主要エンドポイント、エラー形式、OpenAPI突合 | contract-test green、401/403/404/409/422の網羅テスト |
| P10 | Evaluation + Audit + Monitoring | 非同期評価Subscriber、Audit追記、Metrics/Trace | イベント発火→評価記録→監査検索がE2Eで通る |
| P11 | 仕上げ | Helm/Dockerfile、README、prompt-regression、負荷確認 | p99目標（設計書NFR-002/003）をローカル計測で確認 |

**M1の非対象**: Experiment Engine、Search Engine本実装（Fallbackのみ）、Review/Approvalワークフロー UI、実APAP接続。これらは M2 で追加。
