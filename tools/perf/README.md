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
| `OpenAiExecutionAdapterMeasurementTest` | 本実測。レイテンシ・usage・コストを記録する | `PE_OPENAI_API_KEY` **かつ** `PE_OPENAI_RUN_MEASUREMENT=true` | ウォームアップ + 測定分（後述） |

`OpenAiExecutionAdapterMeasurementTest`は`PE_OPENAI_API_KEY`だけでは実行されない
（第2条件`PE_OPENAI_RUN_MEASUREMENT=true`も必須）。接続性確認用に`PE_OPENAI_API_KEY`だけを
設定した状態で`--tests`を付けずに広く`test`を実行しても、既定22回の有償リクエストが
意図せず走らないようにするため。**`OpenAiExecutionAdapterMeasurementTest`のみを明示的に
指定して実行する**（`RealApiTest`は接続性確認用の別目的であり、実測とは分けて余分な課金を
しないため）。

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
（正確な値はモデルのトークナイザ次第であり、実行後の実測値が正）。22リクエスト
（ウォームアップ2 + 測定20）を通じても、入出力合計で数万トークン程度に収まる規模であり、
低価格帯モデルの料金体系では**数十円〜百円未満程度**に収まる可能性が高い。

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
