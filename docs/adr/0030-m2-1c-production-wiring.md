# ADR-0030: M2-1c 本番DI配線と実プロバイダ疎通確認 — ModelProfile供給・リトライ責務・実APIテスト・起動条件

## ステータス

Accepted

## コンテキスト

M2-1a（ADR-0029）で暫定Providerアダプタ（`plugins/execution-openai`）を実装したが、
`prompt-engine-bootstrap`のDI配線は変更せず、本番プロファイルは`FakeExecutionAdapter`の
ガードにより引き続き起動できない状態だった。当初M2-1bとして計画していた
「`ModelProfile`を設定固定値から実メタデータ取得へ」という作業は、**APAPがモデルメタデータを
提供する前提**に立っていたが、APAPが存在しない以上、取得先自体が存在しない。同様に
Issue #31（リトライ責務の重複解消）が想定する二重リトライも、APAP不在の現状では
発生しようがない。M2-1bをM2-1cへ統合し、本番DI配線・実プロバイダでの疎通確認を本フェーズの
スコープとする。

実装前に4点の方針を決定し、承認を得てから着手した（CLAUDE.md「作業の進め方」）。
承認時に追加で2点の要求（コンテキスト長超過エラーの区別、実測手順書の整備）を受けた。

## 決定

### 1. `ModelProfile`の供給源とコンテキスト長超過エラーの区別

設計書§2.11は「Model Profile（APAPのモデルメタデータを参照して構成）」と明記しており、
実メタデータ取得は本来APAP前提の機能である。APAP不在の間、`ModelProfile`は
**設定値として保持**し、価格改定・モデル追加のたびに値を変更してデプロイし直す運用とする
（`ModelProfileProperties`、`promptengine.model-profile.*`、`prompt-engine-bootstrap`、
`@ConfigurationProperties`パターンは既存の`ArchiveGuardProperties`/`IdempotencyClaimProperties`
に倣う）。

設計書§2.12で確認済みの通り、`ModelProfile.costPerToken`は実行時点の値を
`PromptExecutedEvent`へ焼き込む方式が既に確定しており（`CostEvaluationRule`はイベントに
載った値のみを使い、実行のたびに`ModelProfile`を引き直さない）、「静的設定を都度読む」という
本方針は既存の実装と矛盾しない。APAP実装後は、この供給元をconfig直読みからAPAP経由の
動的取得へ移行する（既存の値の使われ方自体は変えない）。

**設定と実モデルの乖離を検知する手段**（追加要求）: `maxContextTokens`が実際のモデルの
上限より大きいと、`OptimizationEngine`のbudget判定が緩すぎてプロバイダ側でエラーになる。
このエラーは元々`ExecutionErrorType.CLIENT_ERROR`（他の4xxと同じ、リトライ不可）に
分類されるが、それだけでは「リクエストが不正だった」という誤った印象を残し、
「`ModelProfile`の設定が実態と合っていない」という本当の原因が運用者に見えない。

`ExecutionErrorType`に新しい値は追加しない。理由: このエラーのリトライ可否は他の
`CLIENT_ERROR`と全く同じ（同一リクエストを再送しても結果は変わらない）ため、ドメインの
リトライ判定ロジックに新しい分岐は不要であり、`ExecutionErrorType`はドメイン全体
（`RetryingExecutionAdapter`・監視の許可リスト等）へ影響する型のため変更コストが見合わない。
代わりに、暫定Providerアダプタ内で応答ボディの`error.code`
（OpenAI互換APIの構造化エラーコード）を検査し、値が`"context_length_exceeded"`の場合のみ
`OpenAiContextLengthExceededException`（固定文言、プロバイダの生メッセージは転記しない、
ADR-0029決定9踏襲）を`cause`に積む。分類は他の4xxと変わらず`CLIENT_ERROR`のまま、
ログのcauseチェーンから設定不備だと即座に分かるようにする（ADR-0029決定5「usage欠落」と
同型の対応: 本Pluginはdomain/plugin-api以外に依存できないため専用メトリクスは発行できず、
`cause`の型・メッセージが唯一の識別子）。

設計書§13.3（Error仕様）は変更不要と判断した。同章はPE自身の公開APIが返すHTTPステータス・
エラーコードの対応表であり、`ExecutionFailedException`は`errorType`の値に関わらず
一律`502 EXECUTION_FAILED`に写像される（既存のGlobalExceptionHandlerの方針）。
コンテキスト長超過はPEのAPI呼出元が対処できる性質のエラーではなく（原因はPE運用者側の
設定）、他の実行失敗と同じ扱いのままで正しい。区別が必要なのはPE運用者向けの診断情報のみであり、
それは上記のcause機構で満たされる。

[costPerToken]の乖離はコスト集計の誤りとして現れるが、自動検知の仕組みは今回設けない
（継続的な価格差分監視は本フェーズのスコープを超える）。

### 2. リトライ責務（Issue #31）

ADR-0014決定7・`RetryingExecutionAdapter`の実装は変更しない（「PE側が一元的に持つ」設計は
既に実装済み）。APAP不在の現状では、Issue #31が懸念する二重リトライは構造的に発生しようが
ない——選択の結果ではなく、他に持てる場所が無いことの帰結である。Issue #31へその旨を
コメントで追記し、再確認条件（APAPが内部で既にリトライ済みの結果を返すのか、エラーを
そのまま透過するのか）は実APAP接続時まで変更しない。

### 3. 実プロバイダAPIを叩くテストの扱い

**CI（`ci.yml`のpull_requestトリガー）には一切組み込まない。** 課金・外部要因による
不安定性を毎PRのゲートに持ち込まない。

- **JUnit5の`@EnabledIfEnvironmentVariable`でスキップを可視化する**（Gradleのtag除外は
  使わない）。tag除外（`RenderLoadSeeder`の`perf`タグと同じ手法）はテスト自体を実行対象から
  除外するため、JUnit XMLに現れず`tests="0"`のまま"BUILD SUCCESSFUL"になる——このプロジェクトで
  繰り返し潰してきた"silent green"そのもの。`@EnabledIfEnvironmentVariable`はテストを
  実行はするが内部でskipとして扱うため、JUnit XMLに`skipped="1"`として残ることを実測で確認した
  （`OpenAiExecutionAdapterRealApiTest`、環境変数未設定状態で`tests="1" skipped="1"`を確認）。
  CIの`test`ジョブにこのXMLを検出し`::notice::`を出すステップを追加し（`.github/workflows/ci.yml`）、
  PRのChecksタブで一目でわかるようにする。
- **キー管理**: GitHub Secretsは使わず、**ローカル限定**とする。ユーザーからの明示的な方針:
  課金対象のAPIキーをエージェント/CIのプロセスに渡す必要が無い（環境変数はシェル履歴・
  プロセス一覧・エラー出力に残りうる）。このプロジェクトが11フェーズかけて「秘密は経路を
  絞る」を徹底してきた原則を、実測のためだけに緩めない。環境変数`PE_OPENAI_API_KEY`
  （テスト専用。`EnvironmentSecretManagerAdapter`が使う`PE_SECRET_`プレフィックスとは
  別の名前空間にし、本番運用のキー供給経路とテスト実行用キーを混同しない）を人が手元で
  設定して明示的に実行する運用にする。
- **実測の実施**: レイテンシ・usage・コストの実測（`OpenAiExecutionAdapterMeasurementTest`、
  複数回・課金を伴う）はユーザー自身が手元で実行する。手順書を`tools/perf/README.md`に
  用意した（使用fixture・ウォームアップ回数と課金への注記・測定回数とその根拠・記録項目・
  想定コストを明記、`render_load_test.sh`の手順書粒度に揃える）。
- `OpenAiExecutionAdapterRealApiTest`（1リクエストのみの接続性確認、CIでも実行されうる形だが
  実際にはキー未設定のため常にスキップされる）と`OpenAiExecutionAdapterMeasurementTest`
  （反復実測、ユーザーが明示的に`--tests`指定して実行）を目的別に分離した。

### 4. 本番プロファイルの起動条件

`ExecutionConfig`に`promptengine.execution.provider`（`fake`|`openai`、既定`fake`——
**M1の挙動を変えない**デフォルト）を追加し、値に応じて`FakeExecutionAdapter`/
暫定Providerアダプタのどちらを`RetryingExecutionAdapter`でラップするか切り替える。

- `FakeExecutionAdapter`自身の`production`ガードは変更しない。`provider=fake`のまま
  productionへデプロイすればこれまで通り起動失敗する。
- `provider`が未知の値の場合はBean生成自体を失敗させる（fail-fast、設定ミスを起動時に検知）。
- 実プロバイダ選択時のAPIキーは`SecretManagerAdapter`（既存Bean、`EnvironmentSecretManagerAdapter`）
  経由で解決する。新しい供給経路は作らない。キー未設定の場合、`checkNotNull`によりBean生成自体を
  失敗させる（fail-fast。ユーザー指摘の通り「最初のリクエストで初めて落ちるのでは遅い」）。
- Helm `values.yaml`の`config.executionProvider`/`secret.executionApiKey`を追加し、
  本番は前者を明示的に`openai`にする運用にする（誤って`fake`のままデプロイした場合は
  既存ガードが守る）。
- テスト:
  - `ProductionProfileGuardTest`（Testcontainers、実際のSpring Boot起動シーケンス）に
    「実プロバイダ選択かつAPIキー未設定→起動失敗」を追加（既存の「Fake選択→起動失敗」と
    同じ手法、原因チェーンの明示確認）。
  - `ExecutionConfigTest`（Spring Contextを起動しない軽量な単体テスト、新設）で
    provider切り替え・fail-fastロジック全体（fake成功/fake+production失敗/実プロバイダ+
    キーあり成功/実プロバイダ+キーなし失敗/未知provider失敗）を検証する。「実プロバイダ+
    キー設定済みで実際に起動できる」経路は、`EnvironmentSecretManagerAdapter`が
    `System.getenv()`を直接読むため（Spring `Environment`経由ではない）
    `ProductionProfileGuardTest`のコマンドライン引数上書きでは到達できず、
    `EnvironmentSecretManagerAdapter`のテスト用コンストラクタ（Mapを直接注入）を使う
    `ExecutionConfigTest`が唯一の検証箇所となる。

**削除範囲の再確認（ADR-0029決定1の更新）**: 本PR後、`ExecutionConfig.kt`
（`prompt-engine-bootstrap`）が暫定Providerアダプタを具体的に参照する。したがって
`git rm -r plugins/execution-openai`だけでは削除が完結しない。実APAP接続時に暫定実装を
取り除く際は、(a) `plugins/execution-openai`ディレクトリ、(b) `prompt-engine-bootstrap`の
依存宣言（`build.gradle.kts`の`implementation(project(":plugins:execution-openai"))`）、
(c) `ProviderNameContainmentTest`、(d) `ExecutionConfig.kt`のprovider分岐・実アダプタ構築処理、
の4点を揃えて削除する必要がある。`ProviderNameContainmentTest`のallowlistは
`ExecutionConfig.kt`（Composition Root、暫定実装を参照する唯一の正当な場所）と、
それを検証する`ExecutionConfigTest.kt`の2ファイルに限定している。

## 影響範囲

- `plugins/execution-openai`: `OpenAiContextLengthExceededException`新設、
  `OpenAiResponseParser`にコンテキスト長超過検出を追加、
  `OpenAiExecutionAdapterRealApiTest`/`OpenAiExecutionAdapterMeasurementTest`新設
- `prompt-engine-bootstrap`: `ModelProfileProperties`/`ExecutionProviderProperties`新設、
  `ExecutionConfig`のprovider切り替え・fail-fast配線、`build.gradle.kts`の依存を
  `testImplementation`から`implementation`へ変更、`application.yml`に
  `promptengine.execution.*`/`promptengine.model-profile.*`追加、
  `ProviderNameContainmentTest`のallowlist拡張、`ProductionProfileGuardTest`拡張、
  `ExecutionConfigTest`新設
- `deploy/helm/prompt-engine`: `values.yaml`/`configmap.yaml`/`secret.yaml`/`_helpers.tpl`に
  実行プロバイダ選択・ModelProfile設定・APIキーSecretの配線を追加
- `tools/perf/README.md`: 実プロバイダAPI実測の手順書を新設
- `.github/workflows/ci.yml`: 実API疎通確認テストのスキップ状況を`::notice::`で通知するステップを追加
- GitHub Issue #31: APAP不在の現状では二重リトライが発生しないことをコメントで記録

## 参照

- [ADR-0029: M2-1a 暫定Providerアダプタ](0029-m2-1a-provider-adapter.md)
- [ADR-0014: Execution Adapter / Output Formatter（P7）のドメイン表現・リトライ方針を確定する](0014-execution-response-parsing.md)
- [ADR-0013: Optimization Engine / Render Engine（P6）のドメイン表現・決定性担保方針を確定する](0013-optimization-render-engine.md)
- `docs/prompts/m2-1c.md`（本フェーズのキックオフプロンプト、原文のまま保存）
- 設計書§2.11（Optimization仕様、Model Profile定義）、§2.12（Evaluation仕様、Cost算出）、§13.3（Error仕様）
- GitHub Issue #31「APAP統合時にリトライ責務の重複を解消する」
