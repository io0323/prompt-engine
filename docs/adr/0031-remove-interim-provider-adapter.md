# ADR-0031: APAPを独立基盤として構築し、PEP内の暫定Providerアダプタを削除する

## ステータス

Accepted

## コンテキスト

設計上PEPはAI Providerに直接触れず、実行はAPAP（AI Provider Abstraction Platform）へ委譲する
構成である（設計書§16拡張ポイント#11、`ExecutionAdapter`のKDoc）。M2-1a/M2-1c（ADR-0029・
ADR-0030）では、APAPが実在しない間の暫定措置として`plugins/execution-openai`
（OpenAI Chat Completions APIへ直接接続する`ExecutionAdapter`実装）をPEP内に置き、本番DI配線
まで行った。

APAPを独立基盤として別途構築する方針が確定した。PEPには既に`plugins/execution-fake`
（`FakeExecutionAdapter`）があり、暫定Providerアダプタを削除すればFakeのみの状態に戻る。
`plugins/execution-openai`をADR-0029決定1で「削除可能性を優先」して配置したのは、まさに
この削除のためである。

削除にあたり、M2-1a/M2-1cで得た知見のうち多くはAPAP自身がそのまま必要とするものであり、
再発見の手間を省くため引き継ぎ先を明記する。

## 決定

### 1. `plugins/execution-openai`をPEPから削除する

以下を削除した（ADR-0030「削除範囲の再確認」に記載した削除対象と一致）。

- `plugins/execution-openai`（サブプロジェクト一式）。`settings.gradle.kts`はディレクトリを
  走査して自動登録する方式のため、ディレクトリ削除により登録も自動的に外れる（明示的な
  記述変更は不要）。
- `prompt-engine-bootstrap`の依存宣言（`build.gradle.kts`の
  `implementation(project(":plugins:execution-openai"))`）
- `ProviderNameContainmentTest`（検証対象の`plugins/execution-openai`自体が無くなるため）
- OpenAI固有の設定: `ExecutionProviderProperties.modelName`
  （`promptengine.execution.model-name`）、APIキーのSecret配線
  （`SecretManagerAdapter`経由の`OPENAI_API_KEY`取得、Helm`secret.executionApiKey`・
  `PE_SECRET_OPENAI_API_KEY`環境変数）。APAPへは具体的なモデル名ではなく`ModelProfile`
  参照名を渡す設計（設計書§13.2）のため、モデル名を直接扱う設定は暫定実装固有だった。
- `gradle/libs.versions.toml`のWireMock依存（`plugins/execution-openai`の契約テスト専用で、
  他に利用箇所が無いため）

### 2. 残すもの

- `ModelProfileProperties`（`promptengine.model-profile.*`）。どのアダプタ実装でも必要な
  provider非依存の容量・単価設定であり、ADR-0030決定1の「configとして保持し、APAP実装後は
  供給元をAPAP経由の動的取得へ移行する」という方針は変わらない。
- `promptengine.execution.provider`による切替機構（`ExecutionProviderProperties`・
  `ExecutionConfig.executionAdapter`）。値を`fake`/`apap`に読み替える。`apap`は将来APAP
  アダプタが実装される際の差し替え先として値だけ認識し、現時点では実装が無いため
  選択時にfail-fastする（`ExecutionConfig`参照）。
- `FakeExecutionAdapter`自身が持つ`production`プロファイルガード（`activeProfiles`に
  `production`が含まれる場合に起動時エラー）。本ADRによる変更を受けない。
- `ExecutionErrorType`・`RetryingExecutionAdapter`（`prompt-engine-domain`・
  `prompt-engine-core`）。これらはPEP自身の責務（ADR-0014決定7、APAP不在の間はPEPが
  リトライを一元的に持つ）であり、暫定Providerアダプタの削除とは独立している。

### 3. APAPへ引き継ぐ知見

以下はADR-0029に実測結果・原則として記録済みであり、APAP実装時に同じ調査を繰り返す必要が
ないよう、APAPスレッド/チームへADR-0029自体を引き継ぐ。

- `HttpConnectTimeoutException`が`HttpTimeoutException`のサブタイプであり、catch順序を誤ると
  接続タイムアウトが全て`READ_TIMEOUT`（リトライ不可）に誤分類される実装上の罠
  （実測で発見、ADR-0029決定3）
- 例外→エラー種別の写像表（`UnknownHostException`/`SSLHandshakeException`/汎用`SSLException`/
  接続再利用中の書き込み後失敗の扱い、ADR-0029決定4）
- 「未送信と確実に言える場合のみリトライ可能種別に分類する」という安全側の原則
  （二重課金防止、ADR-0014決定7・ADR-0029決定4）
- usage欠落時は0埋めで推定せずエラー化し非リトライとする方針、および副作用抑制の手法
  （cause型による運用診断、ADR-0029決定5）
- レスポンスパーサ・ロール変換（`MessageRole`→プロバイダのrole文字列）の実装パターン
  （ADR-0029・ADR-0030の対象コード自体は削除されるが、実装アプローチとして参照可能）
- コンテキスト長超過エラーを他の4xxと区別する手法（`error.code`検査、`ExecutionErrorType`は
  変更せず`cause`で識別可能にする、ADR-0030決定1）

これらの決定・実測結果を記録したADR-0029・ADR-0030自体は削除・書き換えしない
（コードは削除されても意思決定の記録として残す。ADR-0029・ADR-0030の「ステータス」節に
本ADRへのsupersede注記を追記した）。

### 4. 追跡Issueの扱い

- **Issue #31**（APAP統合時のリトライ責務の重複解消）: 維持する。APAP不在の現状では重複が
  発生しようがない状態は変わらず、実APAP接続時に再確認する内容も変わらない。
- **Issue #92**（M2-1c実測未実施）: PEP側でクローズする。実測対象だった
  `OpenAiExecutionAdapterRealApiTest`/`OpenAiExecutionAdapterMeasurementTest`自体が
  `plugins/execution-openai`削除により無くなるため、PEP側にこれ以上追跡すべき対象が無い。
  同種の実測（レイテンシ・usage・コストの実測、エラー経路の実測）がAPAP側で必要になった
  場合は、APAP自身のIssueとして改めて起票する（本Issueの内容を転記する形ではなく、
  APAPの実装状況に応じて仕切り直す——3ヶ月以上前の見積り根拠をそのまま持ち込まないため）。

## 影響範囲

- `plugins/execution-openai`ディレクトリ削除
- `prompt-engine-bootstrap`: `build.gradle.kts`の依存宣言削除、`ExecutionConfig`の
  `apap`プレースホルダ化、`ExecutionProviderProperties`から`modelName`削除、
  `ProviderNameContainmentTest`削除、`ExecutionConfigTest`/`ProductionProfileGuardTest`更新
- `application.yml`: `promptengine.execution.model-name`削除
- `deploy/helm/prompt-engine`: `secret.executionApiKey`・`config.executionModelName`削除、
  対応するConfigMap/Secret/環境変数配線を削除
- `tools/perf/README.md`: 実プロバイダAPI実測の節を削除（対象テストクラスが無くなるため）
- `.github/workflows/ci.yml`: 実API疎通確認テストのスキップ通知ステップを削除
- `gradle/libs.versions.toml`: WireMock依存削除
- GitHub Issue #92をクローズ

## 参照

- [ADR-0029: M2-1a 暫定Providerアダプタ](0029-m2-1a-provider-adapter.md)（削除されたコードの
  設計判断・実測知見の記録として維持）
- [ADR-0030: M2-1c 本番DI配線と実プロバイダ疎通確認](0030-m2-1c-production-wiring.md)
- [ADR-0014: Execution Adapter / Output Formatter（P7）のドメイン表現・リトライ方針を確定する](0014-execution-response-parsing.md)
- GitHub Issue #31「APAP統合時にリトライ責務の重複を解消する」
- GitHub Issue #92「M2-1c: 実プロバイダでの疎通実測を行い、Fakeとの差分を記録する」（クローズ）
