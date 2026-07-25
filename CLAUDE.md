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
Testcontainers + ArchUnit / ktlint + detekt / Micrometer + OpenTelemetry

## モジュール構成
`prompt-engine-domain` / `-application` / `-core` / `-infrastructure` / `-interface` /
`-plugin-api` / `-bootstrap` / `-testkit`（すべて `modules/` 配下）
Javaパッケージルートは `promptengine`。

## モジュール依存の絶対規約（違反はArchUnitで落ちる）
- `prompt-engine-domain` は **他のいかなるモジュール・フレームワークにも依存しない**。
  Spring / Jackson / JPA / SLF4J のimportを書かない。
- `prompt-engine-application` は `prompt-engine-domain` のみに依存する。
- `prompt-engine-core` / `prompt-engine-infrastructure` は
  `prompt-engine-domain` が定義したInterfaceを**実装する側**。逆方向の依存を作らない。
- `prompt-engine-interface` は `prompt-engine-application` のみを呼ぶ。
  Repository実装に直接触れない。
- 具象クラスのDI結線は `prompt-engine-bootstrap` のConfigurationクラスでのみ行う。
- Plugin実装は `prompt-engine-plugin-api` と `prompt-engine-domain` の公開型のみを参照する。
  パッケージは `promptengine.plugin.<category>.<name>`（例: `promptengine.plugin.tokenizer.approx`）。
  `promptengine.plugin` 直下に直接クラスを置くことは禁止（詳細は `docs/adr/0003-plugin-package-naming.md`）。

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
- `prompt-engine-application` は `org.springframework.transaction.annotation..`（トランザクション境界）を唯一の例外として、Spring / Jackson / JPA / SLF4J への依存を禁止する（ArchUnitで機械強制）。

## テスト規約
- 新規のpublicな振る舞いには必ずテストを書く。テストなしのPRは出さない。
- Domain / Core はモック無しの純粋な単体テスト。Springコンテキストを起動しない。
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
