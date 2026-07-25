# Prompt Engine — Claude Code 実装ガイド

| 項目 | 決定内容 |
|---|---|
| 言語 / FW | Kotlin 2.x / Spring Boot 3.x（JDK 21） |
| ビルド | Gradle Kotlin DSL + マルチモジュール + Version Catalog |
| リポジトリ | モノレポ `prompt-engine`（本体）+ 別リポジトリ `prompt-engine-sdk`（多言語クライアント） |
| M1スコープ | Pipeline 12ステージ全縦切り（Execution は Fake APAP Adapter） |
| GitHub | 標準セット（CI / PR・Issueテンプレート / CODEOWNERS / ブランチ保護 / Dependabot） |
| 設計書 | `PromptEngine_設計書.md`（本ガイドは §番号でこれを参照する） |

---

# 1. リポジトリ構成

## 1.1 リポジトリ分割

| リポジトリ | 内容 | 公開単位 |
|---|---|---|
| `prompt-engine` | サーバ本体、標準Plugin、Plugin SPI、デプロイ、設計書 | コンテナイメージ / Maven（`prompt-engine-plugin-api`のみ） |
| `prompt-engine-sdk` | Kotlin/Java・TypeScript・Python クライアントSDK | 各言語パッケージレジストリ |

SDKを分離する理由: サーバのリリースサイクル（週次）とSDKのリリースサイクル（API安定後のみ）を独立させるため。SDKは `prompt-engine` が公開する OpenAPI 定義（`api/openapi.yaml`）を唯一の契約とし、コード生成＋手書きラッパで構成する。契約はCIで相互検証する（§3.4 contract-test）。

## 1.2 `prompt-engine` モノレポ ディレクトリ構成

```
prompt-engine/
├── .github/
│   ├── workflows/
│   │   ├── ci.yml                 # build + test + lint + arch-test（PR必須）
│   │   ├── contract.yml           # OpenAPI差分検出 + SDK契約テスト
│   │   ├── codeql.yml             # 静的セキュリティ解析
│   │   └── release.yml            # tag push → イメージ publish
│   ├── ISSUE_TEMPLATE/
│   │   ├── bug_report.yml
│   │   ├── feature_request.yml
│   │   └── config.yml
│   ├── PULL_REQUEST_TEMPLATE.md
│   ├── CODEOWNERS
│   └── dependabot.yml
├── .claude/
│   ├── commands/                  # Claude Code カスタムスラッシュコマンド（§5.4）
│   │   ├── impl-task.md
│   │   ├── review-arch.md
│   │   └── add-plugin.md
│   └── settings.json              # 権限・フック設定（§5.5）
├── CLAUDE.md                      # Claude Code 用プロジェクト規約（§4）
├── docs/
│   ├── PromptEngine_設計書.md
│   ├── adr/                       # ADR-0001-....md
│   └── dsl/                       # DSL仕様・サンプル .prompt
├── api/
│   └── openapi.yaml               # 唯一のAPI契約（設計書§13と一致）
├── gradle/
│   └── libs.versions.toml         # Version Catalog
├── settings.gradle.kts
├── build.gradle.kts               # 共通規約（subprojects設定）
├── buildSrc/                      # 独自Gradle規約プラグイン
│   └── src/main/kotlin/promptengine.kotlin-conventions.gradle.kts
├── modules/
│   ├── prompt-engine-domain/                # 純粋Kotlin（Spring依存ゼロ）
│   ├── prompt-engine-application/           # UseCase / Command / Query / Pipeline
│   ├── prompt-engine-core/                # Parser/Compiler/Template/Render/Resolver/…
│   ├── prompt-engine-infrastructure/        # Repository実装 / Adapter / Cache / Search
│   ├── prompt-engine-interface/             # REST Controller / DTO / 認可フィルタ
│   ├── prompt-engine-plugin-api/            # Plugin SPI（外部公開・後方互換必須）
│   ├── prompt-engine-bootstrap/             # Spring Boot Application / DI束ね / 設定
│   └── prompt-engine-testkit/               # テスト用Fixture / Fake実装（test-fixtures）
├── plugins/
│   ├── tokenizer-approx/          # 既定Tokenizer
│   ├── validator-policy/          # PII / 禁止語 Rule
│   ├── formatter-json/            # JSON Structured Output
│   └── execution-fake/            # M1用 Fake APAP Adapter
├── deploy/
│   ├── helm/prompt-engine/                  # Chart（api / worker / admin）
│   ├── k8s/                       # 素のマニフェスト（開発用）
│   └── docker/Dockerfile
├── tests/
│   ├── integration/               # Testcontainers（RDB/Broker/Search）
│   ├── contract/                  # OpenAPI ↔ 実装 の契約テスト
│   └── prompt-regression/         # Golden Prompt回帰（renderHash比較）
├── compose.yaml                   # ローカル依存一式（RDB/Broker/Search/Cache）
├── .editorconfig / .gitattributes / .gitignore
├── CONTRIBUTING.md / SECURITY.md / LICENSE / README.md
└── CHANGELOG.md
```

## 1.3 Gradle モジュール依存関係

```
prompt-engine-bootstrap
   ├──> prompt-engine-interface ──> prompt-engine-application ──┐
   ├──> prompt-engine-infrastructure ───────────────────────────┤
   ├──> prompt-engine-core ─────────────────────────────────────┼──> prompt-engine-domain
   └──> plugins/* ──> prompt-engine-plugin-api ─────────────────┘        （公開型のみ）

制約（ArchUnitで機械的に強制）:
- prompt-engine-domain は他モジュール・Spring・JPA・Jackson に一切依存しない
- prompt-engine-application は prompt-engine-domain のみに依存（Interface参照）
- prompt-engine-core / prompt-engine-infrastructure は prompt-engine-domain の Interface を実装する側
- prompt-engine-interface は prompt-engine-application のみ（Repository実装に直接触れない）
- 実装クラスの結線は prompt-engine-bootstrap のみで行う
```

`settings.gradle.kts`（骨子）:

```kotlin
rootProject.name = "prompt-engine"
include(
  "modules:prompt-engine-domain", "modules:prompt-engine-application", "modules:prompt-engine-core",
  "modules:prompt-engine-infrastructure", "modules:prompt-engine-interface", "modules:prompt-engine-plugin-api",
  "modules:prompt-engine-bootstrap", "modules:prompt-engine-testkit",
  "plugins:tokenizer-approx", "plugins:validator-policy",
  "plugins:formatter-json", "plugins:execution-fake",
  "tests:integration", "tests:contract", "tests:prompt-regression",
)
```

## 1.4 技術選定（M1）

| 領域 | 採用 | 理由 |
|---|---|---|
| 永続化 | PostgreSQL + Spring Data JDBC + Flyway | Aggregate境界をORMに侵食されない。マイグレーションを設計書§12のER図と1:1管理 |
| Event Store | 同一PostgreSQLの `domain_events` テーブル（追記専用） | M1では専用ミドルを持たない。Outboxパターンで Broker へ中継 |
| Event Bus | Kafka互換（ローカルはRedpanda） / Spring Kafka | 抽象は `EventBusAdapter`（§16-14）なので差替可 |
| Cache | Redis + `PromptCache` 実装 | Version公開イベントで無効化 |
| Search | OpenSearch互換 | `SearchEngine` 抽象の背後。M1は簡易LIKE検索のFallback実装も用意 |
| Secret | Spring Cloud Vault 互換の `SecretManagerAdapter`（M1はenv実装） | DSLには参照名のみ |
| 認可 | Spring Security Resource Server（JWT）+ CIAP JWKS | スコープはCIAP発行のclaim |
| API文書 | springdoc-openapi（生成物を `api/openapi.yaml` と突合） | 実装と契約の乖離をCIで検出 |
| テスト | JUnit5 + Kotest assertions + MockK + Testcontainers + ArchUnit | Clean Architecture制約を自動検証 |
| 品質 | ktlint + detekt + JaCoCo（domain/engine は行カバレッジ85%以上） | CIゲート |
| 可観測性 | Micrometer + OpenTelemetry Exporter | 設計書§2.15 |

---

# 2. M1 実装計画（全縦切り）

各フェーズは1PR単位。前フェーズがマージ済であることを前提とする。

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

---

# 3. GitHub 設定（標準セット）

## 3.1 ブランチ戦略と保護

- `main`: 常にデプロイ可能。直push禁止。
- 作業ブランチ: `feat/<phase>-<slug>` / `fix/<slug>` / `chore/<slug>`。
- コミット: Conventional Commits（`feat(domain): add PromptVersion lifecycle`）。

`main` のブランチ保護ルール:

| 設定 | 値 |
|---|---|
| Require pull request before merging | ON（承認1名以上） |
| Dismiss stale approvals | ON |
| Require review from Code Owners | ON |
| Require status checks | `build`, `test`, `lint`, `arch-test`, `contract` |
| Require branches up to date | ON |
| Require conversation resolution | ON |
| Require linear history | ON（squash merge のみ許可） |
| Do not allow bypassing | ON（管理者含む） |

## 3.2 `.github/workflows/ci.yml`

```yaml
name: CI
on:
  pull_request:
  push: { branches: [main] }
concurrency:
  group: ci-${{ github.ref }}
  cancel-in-progress: true
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '21' }
      - uses: gradle/actions/setup-gradle@v4
      - name: Build
        run: ./gradlew assemble --no-daemon
  lint:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '21' }
      - uses: gradle/actions/setup-gradle@v4
      - run: ./gradlew ktlintCheck detekt --no-daemon
  arch-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '21' }
      - uses: gradle/actions/setup-gradle@v4
      - name: Clean Architecture制約検証
        run: ./gradlew :modules:prompt-engine-bootstrap:test --tests '*ArchitectureTest' --no-daemon
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '21' }
      - uses: gradle/actions/setup-gradle@v4
      - name: Unit + Integration (Testcontainers)
        run: ./gradlew test integrationTest jacocoTestCoverageVerification --no-daemon
      - uses: actions/upload-artifact@v4
        if: always()
        with: { name: test-reports, path: '**/build/reports/**' }
```

## 3.3 `.github/CODEOWNERS`

```
*                       @org/prompt-engine-maintainers
/modules/prompt-engine-domain/    @org/prompt-engine-architects
/modules/prompt-engine-plugin-api/ @org/prompt-engine-architects   # 後方互換必須、変更は必ずアーキ承認
/api/openapi.yaml       @org/prompt-engine-architects @org/prompt-engine-sdk
/docs/adr/              @org/prompt-engine-architects
/.github/               @org/prompt-engine-maintainers
/deploy/                @org/prompt-engine-sre
```

## 3.4 その他の標準ファイル

- `.github/dependabot.yml`: `gradle`（週次）、`github-actions`（週次）、`docker`（月次）。
- `.github/PULL_REQUEST_TEMPLATE.md`: 変更概要 / 対応する設計書の節番号 / テスト内容 / 破壊的変更の有無 / チェックリスト（ArchUnit通過・OpenAPI更新・ADR要否）。
- `.github/ISSUE_TEMPLATE/`: `bug_report.yml`（再現手順・期待/実際・traceId欄）、`feature_request.yml`（設計書の該当節を必須入力）。
- `contract.yml`: springdoc生成のOpenAPIと `api/openapi.yaml` をdiffし、差分があればPRを落とす。破壊的変更検知は `oasdiff` を使用。
- `release.yml`: `v*` タグでコンテナイメージをGHCRへpush、`prompt-engine-plugin-api` をMaven publish。

---

# 4. `CLAUDE.md`（リポジトリ直下に配置）

以下をそのまま `prompt-engine/CLAUDE.md` として保存する。Claude Code は全セッションでこれを読み込む。

````markdown
# Prompt Engine（PE）

## このリポジトリについて
AIシステム共通のPrompt資産管理基盤。設計書 `docs/PromptEngine_設計書.md` が唯一の仕様源。
実装判断に迷ったら、まず設計書の該当節を読むこと。設計書と実装が矛盾する場合は
**実装を変えるのではなく、まず矛盾を指摘して確認を取る**。

関連基盤との責務分離（越境しないこと）:
- AACP: Agent実行・ワークフロー → Prompt Engine のクライアント
- CIAP: 認証・認可 → PEは検証のみ、ユーザー管理を持たない
- APAP: AIプロバイダ/モデル抽象化 → PEはモデルを直接呼ばない
- PE（Prompt Engine）: Prompt資産の管理・テンプレート・評価・最適化 ← 本リポジトリ

用語注意: 製品名は **Prompt Engine**。Pipeline全体を統括する内部コンポーネントは
**Prompt Core**（モジュール `prompt-engine-core`）と呼び、製品名と混同しないこと。

## 技術スタック
Kotlin 2.x / Spring Boot 3.x / JDK 21 / Gradle Kotlin DSL マルチモジュール /
PostgreSQL + Spring Data JDBC + Flyway / Redis / Kafka互換 / JUnit5 + Kotest + MockK +
Testcontainers + ArchUnit / ktlint + detekt

## モジュール構成
`prompt-engine-domain` / `-application` / `-core` / `-infrastructure` / `-interface` /
`-plugin-api` / `-bootstrap` / `-testkit`（すべて `modules/` 配下）
Javaパッケージルートは `promptengine`。

## モジュール依存の絶対規約（違反はArchUnitで落ちる）
- `prompt-engine-domain` は **他のいかなるモジュール・フレームワークにも依存しない**。
  Spring / Jackson / JPA / SLF4J のimportを書かない。
- `prompt-engine-application` は `prompt-engine-domain` のみに依存する。
- `prompt-engine-core` / `prompt-engine-infrastructure` は `prompt-engine-domain` が定義したInterfaceを**実装する側**。
  逆方向の依存を作らない。
- `prompt-engine-interface` は `prompt-engine-application` のみを呼ぶ。Repository実装に直接触れない。
- 具象クラスのDI結線は `prompt-engine-bootstrap` のConfigurationクラスでのみ行う。
- Plugin実装は `prompt-engine-plugin-api` と `prompt-engine-domain` の公開型のみを参照する。

## コーディング規約
- コンストラクタインジェクションのみ。`@Autowired` フィールド注入は禁止。
- Domain層のクラスは原則 `data class` / `value class` で不変。可変状態を持たない。
- Aggregateの状態変更メソッドは、不変条件を破る呼び出しに対して
  ドメイン例外（`InvalidStateTransitionException` 等）を投げる。null返却で誤魔化さない。
- 例外→HTTPステータスの写像は `prompt-engine-interface` の `GlobalExceptionHandler` に集約。
  エラーコードは設計書§13.3の表と完全一致させる（勝手なコードを増やさない）。
- ログは構造化（key=value）。**Secret / sensitive=true の変数値は絶対に出力しない**。
  マスク処理は `SensitiveValue` 型に閉じ込め、`toString()` は常に `"***"` を返す。
- 公開APIのKDocは必須。内部実装のコメントは「なぜ」だけ書く。「何を」はコードで表す。

## テスト規約
- 新規のpublicな振る舞いには必ずテストを書く。テストなしのPRは出さない。
- Domain / Engine はモック無しの純粋な単体テスト。Springコンテキストを起動しない。
- Infrastructure は Testcontainers を使った統合テスト（`tests/integration`）。
- 決定性が仕様の箇所（Render）は、同一入力から `renderHash` が一致することを検証する。
- テスト名は日本語のバッククォート記法で意図を書く:
  `` fun `Published状態のVersionは内容を変更できない`() ``

## やってはいけないこと
- 特定のAIプロバイダ名・モデル名をコードに直接書かない。モデル依存は `ModelProfile` 経由。
- `prompt-engine-domain` にフレームワークアノテーションを付けない。
- 設計書にない公開APIエンドポイント・イベント・エラーコードを勝手に追加しない。
  必要なら先に `docs/adr/` にADRを起こして提案する。
- DSLのテンプレート式に任意コード実行（eval相当）を実装しない。仕様は設計書§15.1に限定。
- 巨大PRを作らない。1PR = 1フェーズ = レビュー可能な粒度（目安 800行以内）。

## よく使うコマンド
```bash
./gradlew build                 # 全モジュールビルド
./gradlew test                  # 単体テスト
./gradlew integrationTest       # Testcontainers統合テスト
./gradlew ktlintFormat detekt   # フォーマット + 静的解析
docker compose up -d            # ローカル依存（PostgreSQL/Redis/Kafka/OpenSearch）
./gradlew :modules:prompt-engine-bootstrap:bootRun
```

## 作業の進め方
1. 着手前に、対応する設計書の節と既存コードを読む。
2. 変更方針を箇条書きで示し、承認を得てから実装に入る（大きな変更の場合）。
3. 実装 → テスト → `./gradlew build ktlintFormat detekt test` が通ることを確認 → 報告。
4. 不明点は推測で埋めずに質問する。特にドメインルールの解釈は必ず確認する。
````

---

# 5. Claude Code の使い方

## 5.1 立ち上げ手順

```bash
mkdir prompt-engine && cd prompt-engine && git init
# 設計書とガイドを配置
mkdir -p docs && cp ~/PromptEngine_設計書.md docs/
claude
```

最初のセッションで P0 プロンプト（§6.1）を投入する。`CLAUDE.md` は P0 で生成させるのではなく、**§4の内容を先に手で置いてから**開始すると、以降の生成物が規約に沿う。

## 5.2 セッション運用の原則

| 原則 | 理由 |
|---|---|
| 1セッション = 1フェーズ | コンテキストが混ざると設計逸脱が起きる。フェーズ完了後に `/clear` |
| 実装前に Plan mode（`Shift+Tab` 2回）で方針を出させる | 大きな手戻りを防ぐ。方針を承認してから実装させる |
| 設計書の該当節を毎回明示的に指す | 「設計書§2.6の表に従って」と書くだけで精度が大きく上がる |
| テストを先に書かせる | 特に Domain（P1）と Parser（P3）はTDDが有効 |
| `git commit` はフェーズ内でも細かく | Claude に「ここまでをコミットして」と指示。巻き戻しが容易になる |
| 長い探索は Explore サブエージェントに投げる | メインのコンテキストを消費しない |

## 5.3 コンテキスト節約

設計書は大きいため、毎回全文を読ませない。フェーズ開始時に該当節だけ読ませる:

```
docs/PromptEngine_設計書.md の §2.6（Pipeline）と §5.5〜5.7 のシーケンスだけを読んで。
他の節はまだ読まなくていい。
```

## 5.4 カスタムスラッシュコマンド（`.claude/commands/`）

`impl-task.md`:

```markdown
---
description: 設計書の節を指定して実装タスクを実行する
---
以下の手順で実装してください。

1. `docs/PromptEngine_設計書.md` の $ARGUMENTS で指定された節を読む
2. 関連する既存コードを確認する
3. 実装方針を箇条書きで提示し、私の承認を待つ
4. 承認後、テストを先に書き、次に実装する
5. `./gradlew ktlintFormat detekt test` が通ることを確認する
6. 変更ファイル一覧と、設計書との対応関係を報告する

CLAUDE.md のモジュール依存規約を必ず守ること。
```

`review-arch.md`:

```markdown
---
description: 現在の差分がアーキテクチャ規約に違反していないか監査する
---
`git diff main...HEAD` を確認し、以下を厳格に監査してください。
- CLAUDE.md のモジュール依存規約の違反
- prompt-engine-domain へのフレームワーク混入
- 設計書§13.3 にないエラーコードの追加
- Secret/sensitive値がログ・例外メッセージ・キャッシュキーに漏れていないか
- テストが不足している public な振る舞い
違反があれば、ファイル:行 と修正案を示してください。問題なければその旨だけ答えてください。
```

`add-plugin.md`:

```markdown
---
description: 新しいPluginを設計書§16の拡張ポイント規約に沿って追加する
---
$ARGUMENTS で指定された拡張ポイントのPluginを追加します。
1. `docs/PromptEngine_設計書.md` §16 と `modules/prompt-engine-plugin-api` のSPIを読む
2. `plugins/<name>/` にGradleサブプロジェクトを作成し settings.gradle.kts に登録
3. PluginManifest（id/version/extensionPoint/configスキーマ）を実装
4. healthCheck と実行時間上限（Rule系100ms）への対応を実装
5. 単体テストと、PluginManager経由で解決されることの統合テストを書く
Plugin は prompt-engine-plugin-api と prompt-engine-domain の公開型のみ参照すること。
```

## 5.5 `.claude/settings.json`（権限とフック）

```json
{
  "permissions": {
    "allow": [
      "Bash(./gradlew *)",
      "Bash(git status)", "Bash(git diff *)", "Bash(git log *)",
      "Bash(git add *)", "Bash(git commit *)",
      "Bash(docker compose *)"
    ],
    "deny": [
      "Bash(git push --force*)",
      "Bash(rm -rf *)",
      "Read(./**/*.env)",
      "Read(./**/secrets/**)"
    ]
  },
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "Edit|Write",
        "hooks": [
          { "type": "command", "command": "./gradlew ktlintFormat --quiet || true" }
        ]
      }
    ]
  }
}
```

フックで自動フォーマットを掛けると、ktlint起因のCI失敗が実質ゼロになる。

---

# 6. フェーズ別プロンプト集

各プロンプトはそのままコピーして使う。`>>>` 以降が投入文。

## 6.1 P0 — リポジトリ初期化

```
>>>
Kotlin 2.x / Spring Boot 3.x / JDK 21 / Gradle Kotlin DSL で、
docs/PromptEngine_設計書.md §3.1〜3.2 のパッケージ構成に対応した Gradle マルチモジュールの
骨格を作ってください。CLAUDE.md は既に配置済みなので、その規約に厳密に従うこと。

作るもの:
1. settings.gradle.kts（modules/*, plugins/*, tests/* を登録）
2. buildSrc に規約プラグイン promptengine.kotlin-conventions.gradle.kts
   （Kotlin/JVM設定、JDK21、ktlint、detekt、JaCoCo、テスト共通設定）
3. gradle/libs.versions.toml（Version Catalog）
4. 各モジュールの build.gradle.kts と空のパッケージディレクトリ
   - prompt-engine-domain は Spring 依存を一切入れない
   - prompt-engine-bootstrap のみ spring-boot-starter-web / actuator を入れる
5. prompt-engine-bootstrap に ArchitectureTest（ArchUnit）を作り、
   CLAUDE.md のモジュール依存規約6項目をすべてルール化する
6. compose.yaml（PostgreSQL 16 / Redis 7 / Redpanda / OpenSearch）
7. .gitignore / .editorconfig / README.md（起動手順）

完了後 ./gradlew build が通ることを確認し、ArchUnitルールの一覧を報告してください。
まず全体のファイル一覧を提示してから実装に入ってください。
```

続けて GitHub 設定:

```
>>>
本ガイド §3 に従って GitHub 標準セットを作成してください。
- .github/workflows/ci.yml（build / lint / arch-test / test の4ジョブ）
- .github/workflows/codeql.yml
- .github/dependabot.yml（gradle週次・actions週次・docker月次）
- .github/PULL_REQUEST_TEMPLATE.md
  （変更概要 / 対応する設計書の節番号 / テスト内容 / 破壊的変更 / チェックリスト）
- .github/ISSUE_TEMPLATE/bug_report.yml, feature_request.yml, config.yml
- .github/CODEOWNERS（雛形。組織名は @org のまま置いてよい）
- CONTRIBUTING.md（Conventional Commits、ブランチ命名、PR粒度800行の方針）
- SECURITY.md

ブランチ保護は GitHub UI 側の設定なので、必要な設定項目を
README.md の「初期セットアップ」節に手順として書いてください。
```

## 6.2 P1 — ドメインモデル

```
>>>
docs/PromptEngine_設計書.md の §4（ドメイン設計）と §2.5（Promptライフサイクル）だけを読んでください。
他の節はまだ不要です。

prompt-engine-domain モジュールに Prompt Authoring コンテキストのドメインモデルを実装します。
TDD で進めてください。テストを先に書き、私に見せてから実装に移ること。

実装対象:
1. Value Object: PromptKey（正規表現検証つき）, SemVer, VersionRef, PromptContent（SHA-256）,
   VariableDefinition, ContextRequirement, TokenCount, Cost, LatencyMs, SensitiveValue
   - SensitiveValue の toString() は常に "***" を返すこと
2. Entity: PromptVersion
3. Aggregate Root: Prompt
   - §2.5 の遷移表（8遷移）をすべて実装
   - ガード条件（Validation合格・承認数・依存先Published・参照ゼロ）を引数で受け取る形にし、
     Aggregate自身は外部I/Oをしないこと
   - 不正遷移は InvalidStateTransitionException
4. LifecycleState を State パターンで表現（設計書§3.5）
5. Domain Event: 設計書§14 の Prompt 関連イベント（Created/VersionCreated/Published/
   RolledBack/Deprecated/Archived 等）を data class で定義。共通封筒フィールドを持つ基底型を用意
6. Repository Interface（PromptRepository）
   - TemplateRepository / FragmentRepository は、対応するTemplate/Fragment Aggregateを
     実装するフェーズ（P3以降）に移す。P1時点ではPrompt Aggregateのみが存在するため。

不変条件のテストは §4.3 の表を必ず全部カバーしてください。
特に「Published は同時に1Version」「Published内容はImmutable」を落とさないこと。
```

## 6.3 P2 — 永続化 + Event Store

```
>>>
docs/PromptEngine_設計書.md の §12（ER図）と §2.14（Repository仕様）を読んでください。

prompt-engine-infrastructure に永続化層を実装します。
1. Flyway マイグレーション V1__init.sql を §12 のER図から作成
   - 全テーブル、PK/FK/UNIQUE制約、インデックス（prompt_key、trace_id、occurred_at）
   - domain_events は (aggregate_id, sequence) にUNIQUE制約
   - audit_logs は追記専用（UPDATE/DELETEを禁止するDBロールの想定をコメントで明記）
2. Spring Data JDBC で PromptRepository の実装
   - Aggregate単位でロード/セーブ（PromptVersion を子として一括）
   - 楽観ロック（version列）で VERSION_CONFLICT を検出
3. Event Store: domain_events への追記と、Outboxパターンでの Broker 中継
   - 保存とイベント追記は同一トランザクション
4. Snapshot対応（sequence が N 件を超えたらスナップショット保存）
5. tests/integration に Testcontainers（PostgreSQL 16）を使った統合テスト
   - 保存 → 復元 → 内容一致
   - イベント追記順序と sequence の連番性
   - 同時更新での楽観ロック衝突

prompt-engine-domain には一切変更を加えないこと。加える必要が生じたら、その理由を先に説明してください。
```

## 6.4 P3 — DSL Parser / Compiler

```
>>>
docs/PromptEngine_設計書.md の §15（Prompt DSL仕様）全体と、§2.6 のステージ1〜3を読んでください。
ここが本プロダクトの中核です。仕様外の構文を勝手に増やさないでください。

prompt-engine-core に Parser と Compiler を実装します。TDD必須。

1. PromptDslParser
   - YAML Front Matter + 本文 の分離（front matterは snakeyaml、本文は自前パーサ）
   - 本文の構文: {{ expr }} / {{#if}}{{else}}{{/if}} / {{#each list as item}} /
     {{#block role}} / {{> include }} / {{!-- comment --}} / パイプフィルタ
   - 式はプロパティ参照とフィルタのみ。**任意コード実行は実装しない**
   - 構文エラーは行番号・列番号付きの ParseError で返す
2. PromptAst（Composite パターン。TextNode / ExprNode / IfNode / EachNode /
   BlockNode / IncludeNode / MacroCallNode）
3. CompositionService（prompt-engine-domain のDomain Service Interface + prompt-engine-core の実装）
   - extends の単一継承マージ（子のblockが親を上書き、{{ super() }} 対応）
   - imports のエイリアス解決、SemVer範囲（^2, 1.3.0）の解釈
   - include の展開（変数束縛 k=v、未指定は呼出側スコープ透過）
   - macro のローカル展開（再帰呼出は検出してエラー）
   - 循環検出（DFS、訪問済セット）→ CIRCULAR_DEPENDENCY
   - 深さ上限5、展開後サイズ上限1MB
4. CompiledPrompt（AST + 依存一覧 + 変数定義 + Context要件）

テストデータとして docs/dsl/samples/ に正常系5本、異常系（循環・深さ超過・
未定義include・構文エラー・再帰マクロ）5本の .prompt を作り、
全てが期待通りの結果になることを検証してください。
```

## 6.5 P4 — Resolver

```
>>>
docs/PromptEngine_設計書.md の §2.7（Context Flow）と §2.8（Variable Resolution）、
§5.3〜5.4 のシーケンスを読んでください。

prompt-engine-core に Resolver を実装します。

1. VariableResolverChain（Chain of Responsibility）
   - 優先順位: Explicit Parameter → Static → User → Workflow → Environment → Secret（先勝ち）
   - 各Resolverは VariableResolver インターフェースを実装
   - SecretResolver は SecretManagerAdapter（prompt-engine-domain のInterface）経由。
     M1の実装は環境変数バックエンドで prompt-engine-infrastructure に置く
   - 解決値が sensitive=true の場合は SensitiveValue でラップ
   - required が未解決なら VARIABLE_UNRESOLVED（未解決変数名を全部列挙して返す。
     1個目で止めない）
2. ContextResolverImpl
   - 7スコープ（system/user/conversation/workflow/application/memory/environment）
   - マージ順序は §2.7 の通り（environment→system→application→workflow→user→memory→conversation）
   - required 未充足は CONTEXT_UNAVAILABLE、optional 未充足は warning を積んで継続
   - 未宣言スコープへの参照は Validation で落とすので、ここではスコープを注入しないだけでよい
3. BindingSet（不変Map。sensitive値はデバッグ出力でマスク）

テスト観点: 優先順位の全組合せ、未解決時のエラー内容、
BindingSet.toString() に Secret の実値が出ないこと、Contextマージの上書き順序。
```

## 6.6 P5 — Validation Engine

```
>>>
docs/PromptEngine_設計書.md の §2.10（Validation仕様）、§15.7（DSL内validation宣言）、
§5.5 のシーケンスを読んでください。

prompt-engine-core に Validation Engine を実装します。

1. ValidationRule インターフェース（設計書§3.4 の疑似コード通り）
2. 標準Rule 6種を個別クラスで実装
   - SchemaValidation / PlaceholderValidation / ParameterValidation
   - LengthValidation（maxLength と maxTokens。Tokenizerは次フェーズなので暫定推定でよいが、
     TokenizerPlugin インターフェース越しに呼ぶ形にしておくこと）
   - PolicyValidation（Rule Plugin として plugins/validator-policy に実装。禁止語リストは設定注入）
   - DependencyValidation（参照先Statusの検証。COMPILE_ONLYモードではDraft参照を許可）
3. ValidationEngineImpl
   - 全Ruleを実行して Finding を集約（1個目で止めない）
   - severity: ERROR / WARNING / INFO。ERROR が1つでもあれば VALIDATION_FAILED
   - DSLの validation.placeholders: strict|lenient で severity を切り替える
4. ValidationReport（Findingリスト、rule id、path、severity、message）
   - 設計書§13.3 の details 形式にそのまま写せる構造にすること

各Ruleについて正常系1・異常系2以上のテストを書いてください。
```

## 6.7 P6 — Optimization + Render

```
>>>
docs/PromptEngine_設計書.md の §2.9（Rendering）、§2.11（Optimization）、
§5.6〜5.7 のシーケンスを読んでください。

1. plugins/tokenizer-approx に TokenizerPlugin の既定実装
   （文字種別の近似カウント。正確性より決定性と速度を優先。アルゴリズムをKDocに明記）
2. prompt-engine-core に OptimizationEngine
   - OptimizationRule（Strategy）: TokenOptimization / Compression / Expansion /
     ContextOptimization を個別クラスで
   - Compression の切り詰め優先順位は §2.11 の通り（conversation古い順 → memory）
   - ModelProfile（maxContextTokens / tokenizerId / costPerToken / capabilities）を
     設定から読む。**プロバイダ名・モデル名をコードに書かないこと**
   - 予算超過は TOKEN_BUDGET_EXCEEDED
   - 変更内容を OptimizationReport（適用Rule、削減トークン数）に残す
3. prompt-engine-core に RenderEngine と DefaultTemplateEngine（id = "pe-tmpl/1"）
   - TemplateEngine インターフェース経由でのみ展開（差替可能性を担保）
   - 出力は RenderedPrompt（messages[{role, content}], outputFormat, tokenEstimate, renderHash）
   - role は system/user/assistant/tool の抽象role のみ
   - renderHash = SHA-256(正規化messages + engineId + engineVersion)。
     **sensitive値はhash計算に含めた後、保持するcontentではマスクしない**
     （実行に必要なため）が、ログ・キャッシュ・Auditへの出力経路では必ずマスクすること

決定性テストを必ず書くこと:
同一の (AST, BindingSet, ModelProfile, engineVersion) から100回renderして
renderHash が全て一致すること。
```

## 6.8 P7 — Execution + Response Parsing

```
>>>
docs/PromptEngine_設計書.md の §2.6 のステージ9〜10、§5.8〜5.9 を読んでください。

1. prompt-engine-domain に ExecutionAdapter / OutputFormatter インターフェース（§3.4の疑似コード通り）
2. plugins/execution-fake に FakeExecutionAdapter
   - 設定したシナリオ（正常応答 / 遅延 / エラー / 不正JSON）を返せること
   - usage（inputTokens/outputTokens）とlatencyを模擬
   - 実APAP接続は M2 なので、ここでは APAP のリクエスト/レスポンス形状を
     ExecutionRequest / RawResponse として抽象化するだけに留める
3. plugins/formatter-json に JsonOutputFormatter
   - instruction(schema): JSON Schema から出力指示文を生成
   - parse(raw, schema): コードフェンス除去 → パース → Schema検証 → ParsedOutput
4. TextOutputFormatter（prompt-engine-core 内蔵の既定）
5. ExecutionPolicy（timeoutMs / maxRetries / backoff / parseRepair）
   - リトライは指数バックオフ。冪等でない副作用がないことをKDocに明記
   - parse失敗かつ parseRepair=true なら修復プロンプトで最大N回再実行

テスト: Fakeの各シナリオでの挙動、リトライ回数、
不正JSON→修復リトライ→成功／最終失敗（PARSE_FAILED）の両パス。
```

## 6.9 P8 — Pipeline Orchestrator

```
>>>
docs/PromptEngine_設計書.md の §2.6（Pipeline 12ステージの表）と §10（アクティビティ図）を読んでください。
この表の入出力・エラーコードと1文字も違わない実装にしてください。

prompt-engine-application に Pipeline を実装します。
1. PipelineStage インターフェースと PipelineContext（immutable更新）
2. 12ステージを個別クラスで実装（Load / Merge / Import / ResolveVariables /
   ResolveContext / Validation / Optimization / Rendering / Execution /
   ResponseParsing / Evaluation / Audit）
   - 各ステージは既存のEngineに委譲するだけの薄い層にすること
   - Evaluation ステージは同期処理せず、イベント発行のみ（非同期評価）
   - Audit ステージは失敗させない。失敗時はDLQへ退避してログに記録
3. PipelineFactory（Factory パターン）
   - RENDER_ONLY = ステージ1〜8
   - FULL_EXECUTION = 1〜12
   - COMPILE_ONLY = 1〜3 + 6
4. PipelineOrchestrator
   - ステージごとに OpenTelemetry Span を作り、所要時間を記録
   - StageError → 設計書§13.3 のエラーコードへ写像
   - traceId を全ステージとイベントに伝播

E2Eテスト（tests/integration）: 3モードそれぞれで、
サンプル .prompt を使った通し実行が成功すること。
各エラーコードが正しいステージから出ることも検証してください。
```

## 6.10 P9 — REST API + 認可

```
>>>
docs/PromptEngine_設計書.md の §13（API設計）全体を読んでください。
表に無いエンドポイント・エラーコードを追加しないこと。

prompt-engine-interface に REST層を実装します。
1. Controller: §13.1 の表のうち M1 対象分
   （Prompt CRUD / Version / diff / lifecycle遷移 / compile / render / execute /
    aliases / dependencies / audit-logs / metrics）
   ※ experiments 系は M2 なので実装しない
2. DTO は §13.2 の JSON 例と完全一致させる（フィールド名・ネスト構造）
3. GlobalExceptionHandler: §13.3 の HTTP↔code 対応表を網羅
4. Spring Security Resource Server（JWT）
   - CiapAuthAdapter で JWKS 検証。スコープ（prompt:read/write/review/approve/
     publish/execute/admin, audit:read）を @PreAuthorize で強制
   - 401 UNAUTHENTICATED / 403 PERMISSION_DENIED を正しく出し分ける
5. Idempotency-Key の処理（全POST。同一キーの再送は初回レスポンスを返す）
6. ページング（既定20・上限100）、X-Trace-Id の受け取りと伝播
7. springdoc-openapi の設定と、生成物を api/openapi.yaml として出力するGradleタスク

tests/contract に、api/openapi.yaml と実装の整合を検証する契約テストを作ってください。
認可テストは各エンドポイントについて「スコープ有り200 / 無し403 / 無トークン401」を網羅。
```

## 6.11 P10 — Evaluation + Audit + Monitoring

```
>>>
docs/PromptEngine_設計書.md の §2.12（Evaluation）、§2.15（Monitoring）、§14（イベント一覧）を読んでください。

1. Event Bus 抽象（EventBusAdapter）と Kafka実装（prompt-engine-infrastructure）
   - トピックは §14 の通り（pe.prompt / pe.execution / pe.evaluation /
     pe.experiment / pe.governance / pe.plugin）
   - イベント封筒 {eventId, eventType, occurredAt, aggregateType, aggregateId,
     actor, traceId, payload} を厳守
2. EvaluationEngine（prompt-engine-core）
   - PromptExecuted を購読して非同期評価
   - M1の評価器: Latency / TokenUsage / Cost（usage × ModelProfile単価）
   - EvaluationRule インターフェース経由にして、Quality系Evaluatorは後から
     Pluginで足せる構造にすること
   - 結果を EvaluationRepository に保存し PromptEvaluationCompleted を発行
3. AuditEngine
   - 全イベントを購読して audit_logs へ追記。payload の Secret はマスク済であることを
     テストで保証すること
   - GET /audit-logs の検索実装
4. Subscriber群: Cache Invalidator（PromptPublished等で invalidateByPrompt）、
   Search Indexer（M1は簡易実装でよい）
5. Micrometer メトリクス: §2.15 の項目
   （pipeline_stage_duration / render_count / cache_hit_ratio /
    validation_failure_count / token_usage_total / cost_total / execution_success_rate）
6. 構造化ログ（JSON）と、Secretマスクを強制する LogSanitizer

E2Eテスト: execute実行 → イベント発行 → 評価記録 → 監査検索でヒット、が一連で通ること。
```

## 6.12 P11 — 仕上げ

```
>>>
M1 の仕上げをします。

1. deploy/docker/Dockerfile（マルチステージ、distrolessまたはjre-alpine、非rootユーザ）
2. deploy/helm/prompt-engine/（api / worker / admin の3 Deployment、HPA、ConfigMap、Secret参照、
   liveness/readiness は actuator）
3. tests/prompt-regression: Golden Prompt回帰テスト
   - サンプル .prompt 一式について renderHash を golden ファイルに固定
   - 差分が出たら失敗し、意図的変更なら golden 更新手順をREADMEに書く
4. README.md の完成（アーキ概要図・起動手順・モジュール説明・
   設計書へのリンク・初期GitHub設定手順）
5. 性能確認: /render と /execute(Fake) をローカルで負荷計測し、
   設計書 NFR-002（p99≤20ms, キャッシュヒット時）と NFR-003（p99≤200ms）に対する
   実測値をREADMEに記録。未達なら原因分析を報告

最後に、M1 の実装が設計書 §1.8 の機能要件表（FR-001〜FR-024）のどれを満たし、
どれが未実装かを表にまとめてください。
```

## 6.13 汎用プロンプト（随時使用）

**設計逸脱の監査**

```
>>>
/review-arch
```

**フェーズ完了時のセルフレビュー**

```
>>>
このフェーズの実装を、実装者ではなくレビュアーの視点で厳しく点検してください。
観点: 設計書との差異 / 不変条件の抜け / テストされていない分岐 /
Secret漏洩経路 / 例外時のリソースリーク / 命名の一貫性。
問題は「ファイル:行 → 何が問題 → どう直すか」の形式で列挙してください。
自分の実装を擁護せず、率直に指摘すること。
```

**設計書とのトレーサビリティ確認**

```
>>>
docs/PromptEngine_設計書.md の §1.8 機能要件表と現在の実装を突き合わせ、
FR-001〜FR-024 それぞれについて「実装済/部分実装/未実装」と、
該当するクラス・ファイルを表にしてください。推測で「実装済」と書かないこと。
```

**PR作成**

```
>>>
現在の変更で PR を作成してください。
- Conventional Commits 形式のタイトル
- 本文は .github/PULL_REQUEST_TEMPLATE.md の形式に従う
- 「対応する設計書の節番号」を必ず埋める
- 破壊的変更があれば明記
```

---

# 7. `prompt-engine-sdk` リポジトリ

```
prompt-engine-sdk/
├── .github/workflows/
│   ├── ci.yml            # 各言語のビルド・テスト
│   └── release.yml       # tag → npm / PyPI / Maven publish
├── spec/openapi.yaml     # prompt-engine リポジトリから同期（CIで差分検出）
├── kotlin/               # Kotlin/Java SDK（Ktor client）
├── typescript/           # TS SDK（fetch ベース、型は生成）
├── python/               # Python SDK（httpx）
├── scripts/sync-spec.sh  # prompt-engine の openapi.yaml を取得して差分PRを作る
└── README.md
```

SDK生成プロンプト:

```
>>>
spec/openapi.yaml から TypeScript クライアントSDKを作ってください。
- 型定義は openapi-typescript で生成し、生成物は src/generated/ に置く（手編集禁止をREADMEに明記）
- 手書きラッパ PromptEngineClient を src/client.ts に実装
  - render(promptKey, params, options) / execute(...) / compile(...)
  - 認証はコンストラクタで tokenProvider を受け取る形（トークン取得はSDKの責務外）
  - エラーは PromptEngineError（code, message, details, traceId）に正規化
  - リトライは 429 と 502 のみ、指数バックオフ、上限3回
- MSW を使ったモックテスト
- 生成物と手書きを混在させない構造にすること
```

---

# 8. 進行チェックリスト

```
[ ] docs/PromptEngine_設計書.md を配置
[ ] CLAUDE.md を配置（§4をそのまま）
[ ] .claude/settings.json と .claude/commands/ を配置
[ ] P0: Gradle骨格 + ArchUnit + CI      → PR #1
[ ] GitHub ブランチ保護を UI で設定
[ ] P1: ドメインモデル                    → PR #2
[ ] P2: 永続化 + Event Store             → PR #3
[ ] P3: DSL Parser / Compiler            → PR #4
[ ] P4: Resolver                          → PR #5
[ ] P5: Validation Engine                 → PR #6
[ ] P6: Optimization + Render             → PR #7
[ ] P7: Execution + Parsing               → PR #8
[ ] P8: Pipeline Orchestrator             → PR #9
[ ] P9: REST API + 認可                   → PR #10
[ ] P10: Evaluation + Audit + Monitoring  → PR #11
[ ] P11: 仕上げ + 性能確認                 → PR #12
[ ] prompt-engine-sdk 初期化（P9完了後）
```

M2以降の候補: 実APAP Adapter、Experiment Engine（A/B・Canary・多腕バンディット）、
Search Engine本実装（全文＋ベクトル）、Review/Approvalワークフロー、
管理UI（別リポジトリ推奨）、Plugin サンドボックス強化。
