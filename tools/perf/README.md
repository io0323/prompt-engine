# tools/perf/

## render_load_test.sh
NFR-003（Render p99 ≤ 200ms）の性能測定（P11）。`deploy/docker/Dockerfile`でビルドした実イメージを
CPU/メモリ制限付きで起動し、`/render`へcurl（接続再利用）で負荷をかける。手順はREADME
「性能測定」節を参照。

## 実プロバイダAPI実測（M2-1c）

`OpenAiExecutionAdapter`のレイテンシ・`usage`・コストを、実際のOpenAI APIに対して実測する手順。
**課金が発生し、APIキーが必要**。CIには一切組み込まれていない——このプロジェクトの秘密管理原則
（経路を絞る）に従い、**キーはあなた自身の手元でのみ使用し、エージェント/CIのプロセスには渡さない**。

### 前提

- `PE_OPENAI_API_KEY`環境変数に有効なOpenAI APIキーを設定できること。
  **用途を限定した使い捨てキー**（プロジェクト単位・低い上限額のもの）を作り、計測後に失効させることを推奨する。
- `./gradlew`が使えること（Docker/Testcontainers・DB接続は不要——`OpenAiExecutionAdapter`を
  直接呼び出すのみで、Pipeline全体やDBは経由しない）。

### 実行するテスト・fixture

`plugins/execution-openai`の2つのテストクラスが実行対象。どちらもJUnit5の
`@EnabledIfEnvironmentVariable`で`PE_OPENAI_API_KEY`未設定時は自動的にスキップされる
（CIで実行されないのはこのため。「スキップされた」事実はJUnit XMLの`skipped`件数として残る）。

| クラス | 目的 | 有効化条件 | リクエスト数 |
|---|---|---|---|
| `OpenAiExecutionAdapterRealApiTest` | 接続性の最小確認（CIでも実行されうる形。ただしCIではキー未設定のため常にスキップ） | `PE_OPENAI_API_KEY` | 1 |
| `OpenAiExecutionAdapterMeasurementTest` | 本実測（2つの`@Test`メソッド。下記参照） | `PE_OPENAI_API_KEY` **かつ** `PE_OPENAI_RUN_MEASUREMENT=true` | ウォームアップ + 測定分（後述） + エラー経路3件 |

`OpenAiExecutionAdapterMeasurementTest`は`PE_OPENAI_API_KEY`だけでは実行されない
（第2条件`PE_OPENAI_RUN_MEASUREMENT=true`も必須）。接続性確認用に`PE_OPENAI_API_KEY`だけを
設定した状態で`--tests`を付けずに広く`test`を実行しても、既定25回の有償リクエストが
意図せず走らないようにするため。**`OpenAiExecutionAdapterMeasurementTest`のみを明示的に
指定して実行する**（`RealApiTest`は接続性確認用の別目的であり、実測とは分けて余分な課金を
しないため）。`--tests`をクラス単位で指定すると、クラス内の2つの`@Test`メソッド
（正常系の反復計測・エラー経路の実測）が両方実行される。

fixtureは`tests/prompt-regression`のGolden Prompt回帰テストが使う本番相当サイズのfixture
（`04-production-scale-support-agent.prompt`、長いsystemブロック + 複数のfew-shot例、P11の
性能測定でも使用したのと同じもの）と同一内容を、テストコード内に直接埋め込んだもの
（`plugins/execution-openai`は`prompt-engine-core`のDSL/RenderEngineへ依存できないため、
DSLファイルをパースせず同じ内容を[RenderedMessage]として再現している）。

### ウォームアップ

既定2回（`PE_OPENAI_WARMUP_COUNT`で変更可）。**ウォームアップも課金対象**。ローカルインフラの
負荷測定（`render_load_test.sh`の数千回）と異なり、実プロバイダAPIへの初回リクエストで
JVMのHTTPクライアント初期化・TLSハンドシェイクの初回コストを1回吸収すれば十分という判断で、
最小限の2回に留めている（プロバイダ側の処理時間自体はJVM側のウォームアップで変化しない）。

### 測定回数

既定20回（`PE_OPENAI_MEASURE_COUNT`で変更可）。根拠: 本フェーズの目的は厳密なp99 SLA認定
ではなく「Fakeと実物でどこが崩れるか」を洗い出すことであり（M2-1cプロンプト参照）、
統計的に確定的なp99を得るには数百回規模の有償リクエストが必要になり目的に対して過大。
20回であれば典型的なレイテンシのばらつき・中央値の傾向を把握するには十分で、課金額も
小さく抑えられる。

### エラー経路の実測（3リクエスト、Issue #92）

正常系の反復計測だけでは、ADR-0014/ADR-0029のリトライ方針・分類ロジックが前提とする
「プロバイダの4xx応答の形」を検証できない。契約テスト（`OpenAiExecutionAdapterContractTest`）
はWireMockのスタブに対してしか通っておらず、実プロバイダが本当にその形（`error.code`等）で
返すかは未確認のまま——ADR-0014/ADR-0029の前提が崩れるとすれば、まずここである。

3ケースとも1リクエストのみで、認証・モデル解決・入力検証のいずれかの段階で拒否される想定
のため課金はほぼ発生しない。

| ケース | 内容 | 想定される課金 |
|---|---|---|
| コンテキスト長超過 | `ModelProfile.maxContextTokens`を意図的に大きく上回る入力を送る | 最小（モデル実行前に拒否） |
| 無効なAPIキー | 意図的に不正な`Authorization`ヘッダで送る | なし（認証段階で拒否） |
| 存在しないモデル名 | `ModelProfile`に存在しないモデル名で送る | なし（モデル解決段階で拒否） |

各ケースについて、`OpenAiExecutionAdapter`を経由せず生のHTTPで直接送信し、
(a) 実際に返ってきたHTTPステータス、(b) レスポンス本文（先頭500文字）、
(c) `OpenAiResponseParser`（実際に使われているのと同じ分類ロジック）が算出する
`ExecutionErrorType`とその`cause`、の3点を標準出力に記録する。契約テストの想定と
実際の分類が食い違っていた場合、それが本フェーズで最も重要な発見になる。

### 記録される項目

`OpenAiExecutionAdapterMeasurementTest`の標準出力に、リクエストごとに以下を出力する。

- レイテンシ（ミリ秒）
- `usage`の実際の値（`inputTokens`/`outputTokens`）
- レスポンス本文の先頭60文字（構造確認用。全文は出力しない）

全リクエスト完了後、p50/p99レイテンシ・合計/平均トークン数のサマリを出力する。
**コスト自体はこのテストでは算出しない**（`ModelProfileProperties`の設定値と実測`usage`を
突き合わせて手計算するか、プロバイダの請求ダッシュボードで確認すること）。

### 想定コスト（実行前の目安）

使用モデル`gpt-4o-mini`は執筆時点で公開されている料金の中でも低価格帯のモデル。
fixture（システムプロンプト + few-shot 3往復 + ユーザー入力）の入力トークン数はおおよそ
数百〜1000トークン程度、応答は短い場合が多く数十〜数百トークン程度と見積もられる
（正確な値はモデルのトークナイザ次第であり、実行後の実測値が正）。25リクエスト
（ウォームアップ2 + 測定20 + エラー経路3）を通じても、入出力合計で数万トークン程度に
収まる規模であり、低価格帯モデルの料金体系では**数十円〜百円未満程度**に収まる可能性が高い。
エラー経路3件は上記表の通りほぼ課金が発生しない（コンテキスト長超過の入力自体は課金対象の
出力トークンを生まない）。

**ただし価格は変動するため、実行前に必ずOpenAIの公式料金ページで現在の`gpt-4o-mini`単価を
確認すること。** 上記はモデル選定時点の一般的な料金水準からの見積りであり、保証値ではない。

### 実行コマンド

```bash
export PE_OPENAI_API_KEY="sk-..."   # 使い捨てキー推奨
export PE_OPENAI_RUN_MEASUREMENT=true   # 反復計測（課金あり）を明示的に許可する第2条件

# 既定（ウォームアップ2回 + 測定20回）で実行
./gradlew :plugins:execution-openai:test \
  --tests "promptengine.plugin.execution.openai.OpenAiExecutionAdapterMeasurementTest" \
  --rerun

# 回数を変える場合
PE_OPENAI_MEASURE_COUNT=10 PE_OPENAI_WARMUP_COUNT=1 \
  ./gradlew :plugins:execution-openai:test \
  --tests "promptengine.plugin.execution.openai.OpenAiExecutionAdapterMeasurementTest" \
  --rerun

# 実行後、キーを失効させる（OpenAIダッシュボード側の操作）
unset PE_OPENAI_API_KEY PE_OPENAI_RUN_MEASUREMENT
```

`--rerun`はGradleのUP-TO-DATE判定でテストがスキップされる（＝実行されない）のを避けるため
（前回の実行結果がキャッシュされていると、入力に変化が無い限り再実行されない）。

結果は標準出力にそのまま表示される（`./gradlew`の`test`タスクはデフォルトで
`@Test`内の`println`出力を表示しないため、標準出力を見るには
`--info`か、結果をコピーする場合は`build/test-results/test/TEST-...MeasurementTest.xml`の
system-outセクションを参照すること）。
