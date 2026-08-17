# ADR-0035: Benchmark（オフライン評価）のAggregate・Golden Dataset・非同期実行方式を確定する

## ステータス

Accepted

## コンテキスト

FR-015（設計書§1.8）は「A/B Test、Canary、Benchmark、トラフィック分割、統計判定」を要求する。
ADR-0034（M2-4a）はこのうちA/Bテスト・Canary（オンライン実験）を対象とし、Benchmark（オフライン
評価）は「別ADR・別PR」（`docs/prompts/m2-4a.md`、`ExperimentType`のKDoc）として明示的に
先送りした。本ADRはその残り、Benchmarkを対象とする（`docs/prompts/m2-4b.md`）。

設計書を読むと、Benchmarkに関する記述は以下の通り不完全・相互に緊張関係がある。

- §2.12は Accuracy/Consistency/Determinism を「Benchmark時」に算出するものとして定義するが、
  対応する永続化モデル（Golden Dataset）が§12に存在しない。
- §12のER図は`experiments.type`のコメントに`AB/CANARY/BENCHMARK`と書き、同じAggregateを
  想定しているように見える。しかし§4.3のExperiment不変条件（`Variant(2..n)`・`配分合計=100%`）は
  オンラインのトラフィック分割を前提にしたものであり、オフライン評価であるBenchmarkには
  Variantもトラフィック配分も存在しない。
- §13.1にBenchmark用エンドポイントは無い。
- §16-6（EvaluationRule拡張ポイント）の既定実装はLatency/Token/Costの3種のみで、
  Accuracy/Consistency/Determinismを表現できる形になっていない。

本ADRはこれらを解消し、Benchmarkの永続化モデル・実行方式・評価器拡張点を確定する。

## 決定

### 1. Benchmarkは別Aggregateとする（Experimentの不変条件は一切変更しない）

`Experiment.requireValidVariants()`は`type`に関わらず無条件に「Variant 2件以上」「重み合計100」を
要求する。`ExperimentType` enumも現状`{AB, CANARY}`のみで、`BENCHMARK`を追加してこの検証を
`type`で分岐させることは可能だが、それは以下の理由で採らない。

- ADR-0034決定1が「Published同時1Version」を緩めず別経路（`ExperimentResolvedVersion`）で
  解決したのと同じ判断を、ここでも一貫させる。「影響範囲を洗い出しきれない不変条件の変更」を
  避け、既存の`Experiment`・`Variant`・`TrafficPolicy`・`variants`テーブル（`version_id`・
  `weight_pct`ともに`NOT NULL`）には一切手を入れない。
- Benchmarkはトラフィックを持たない。`TrafficPolicy`（sticky routing）・`TrafficSplitStrategy`は
  概念として無関係であり、`type`分岐で共存させると「使われないフィールドが存在する行」を
  常態化させる。

新規Aggregate `Benchmark`（`promptengine.domain.benchmark`）を導入する。テーブルは
`experiments`/`variants`を再利用せず、`benchmarks`/`benchmark_targets`/`benchmark_metrics`/
`benchmark_item_results`を新設する（詳細はテーブル定義節）。§12の`experiments.type`コメントは
`AB/CANARY`に修正し、`BENCHMARK`が別Aggregateである旨を注記する。

**諦めたもの**: `experiments`テーブルを共有した場合に得られたはずの「全Experiment種別の一覧を
1クエリで取れる」という性質、および`TrafficSplitStrategy`/`PromotionService`のコード再利用
（`PromotionService`のWelch's t-test計算自体は決定6で将来的に呼び出し側を分けて再利用できる
余地を残す）。

### 2. Golden DatasetはPromptに従属する新規テーブル

`golden_datasets`（`dataset_id`, `prompt_id` FK, `name`, `description`）と`golden_dataset_items`
（`item_id`, `dataset_id` FK, `parameters` JSONB, `context` JSONB, `expected_output`
nullable TEXT, `metadata` JSONB）を新設する。

- **保持先**: DB（Postgres）。Object Storageは導入しない。このリポジトリのローカル依存
  （`compose.yaml`）にS3/MinIO相当は無く、M2で新たなストレージ依存を増やす理由が無い。
  データセットは小〜中規模を前提とする。
- **Prompt/Versionとの関連**: `Prompt`単位（`prompt_id`）。特定のVersionではなくPromptに
  従属させる。理由: 同じデータセットを複数Versionの比較評価（決定6）に使い回せる必要が
  あるため、Version単位に紐付けると比較の度に複製が要る。
- **`expected_output`はnullable**: Consistency/Determinismは期待出力を必要とせず、
  同一入力のN回実行同士を比較するだけで成立する（決定5）。Accuracyを算出する場合のみ
  実質必須（アプリケーション層でAccuracy算出時に検証する。DB制約では強制しない）。
- Domain Aggregate `GoldenDataset`は`items: List<GoldenDatasetItem>`を内包する
  （`Experiment`が`variants: List<Variant>`を内包するのと同じ形）。

### 3. Benchmark実行は非同期ワーカー + 項目単位Claim/フェンシング

データセット件数 × Target数 × N回の実行が必要になるため、同期APIには収まらない。このリポジトリに
既存の「非同期ジョブ+進捗照会」パターンは無い（Outboxは cross-aggregate イベント配送専用で
ジョブ実行機構ではない）。`IdempotentCommandExecutor.executeLongRunning`も「数秒〜数十秒」の
単発リクエストを前提にしており（既定claim timeout 120秒）、Benchmark全体の実行時間を
賄う設計ではない。

- `POST .../benchmarks`は`Pending`状態のBenchmarkを作成し即座に201を返す（実行はしない）。
- `benchmark_item_results`（`target_id` × `item_id`の組ごとに1行、`UNIQUE(target_id, item_id)`）を
  作業単位とする。`@Scheduled`ワーカー（`OutboxRelayScheduler`と同じ形、`prompt-engine-bootstrap`の
  `production`プロファイル専用Configurationで結線）がこのテーブルをポーリングする。
- **Claimの粒度は項目単位（`benchmark_item_results`の行単位）とする**、Benchmark単位（
  `benchmarks`の行単位で1インスタンスが全件を保持し続ける方式）は採らない。
  - Benchmark単位Claimは、1インスタンスが長時間ロックを持ち続けるため、`claimed_at`の
    stale判定用タイムアウトをBenchmark全体の実行時間に合わせて長く取るか、途中で
    heartbeatによる再更新が要る。件数×N回はPromptの内容次第で数分〜数時間の幅があり、
    固定タイムアウトでは安全域を見積もれない。
  - 項目単位Claimは、1件の実行（せいぜい数秒〜数十秒）に対してタイムアウトを設定すれば
    足り、heartbeatが不要。複数インスタンスが同じBenchmarkの異なる項目を並行処理できる
    （スループット向上）。Claim自体のDBオーバーヘッドは1項目あたり最低1回の実プロバイダ
    呼出（秒オーダー）に対して無視できる。
  - 以上により項目単位を選ぶ。
- **Claim+フェンシングはADR-0025（Outbox）/ADR-0027（IdempotentCommandExecutor）と同一パターン**
  を踏襲する。
  1. Claim（短いトランザクション）: `status='Pending' OR (status='Claimed' AND claimed_at <
     stale境界)`な行を`SELECT ... FOR UPDATE SKIP LOCKED`し、`claimed_at=now()`,
     `claimed_by=:instanceId`, `status='Claimed'`を設定してコミット。
  2. 実行（トランザクション外）: 対象Versionのパイプラインを、当該項目の`parameters`/
     `context`でN回実行する（1セットのN回実行をAccuracy/Consistency/Determinismの
     3指標すべてに使い回す。指標ごとに別実行はしない＝実行回数はデータセット件数×
     Target数×Nであり、要求した指標数に比例して増えない）。
  3. 確定（短いトランザクション、フェンシング付き）: `UPDATE benchmark_item_results SET
     status='Completed', accuracy_score=..., ... WHERE result_id=:id AND
     claimed_by=:instanceId`。**`claimed_by`をWHERE句に含めることが必須**
     （ADR-0025が実装当初に欠落させ、CodeRabbitレビューで発覚した不具合と同じ形。
     欠落させると、タイムアウト後に別インスタンスが再claimした行を元インスタンスが
     無条件に上書き確定できてしまう）。0行更新（フェンシング喪失）の場合は例外を投げず、
     `benchmark_fencing_lost`をSLF4J WARNへ構造化ログ出力する
     （`OutboxRelayer.logFencingLost`/`JdbcIdempotentCommandExecutor.logFencingLost`と
     同じ命名規則・ログ形式）。
- 失敗時（実行中の例外）はM2では自動リトライしない。`status='Failed'`・`error_message`を
  記録し、`claimed_at`/`claimed_by`をクリアする（同じフェンシング条件で更新）。実プロバイダ
  課金を伴う操作の自動再試行は、失敗原因（一時障害か恒久障害か）を区別できないまま
  再課金するリスクがあるため、M2では見送る（Outboxのような無限バックオフ再試行は
  対象外）。再実行は利用者がBenchmarkを作り直すことで行う。
- 中断: `POST .../benchmarks/{id}/cancel`は`benchmarks.status`を`Cancelling`に遷移させる。
  ワーカーは項目のClaim前に親Benchmarkの状態を確認し、`Cancelling`ならその項目をClaimせず
  Benchmark全体を`Cancelled`へ遷移させる（実行中の項目を強制中断はしない。項目単位の
  実行は数秒〜数十秒で終わるため、次のポーリングまで待てば十分早い）。
- 再開: 項目ごとに結果を永続化しているため、ワーカー再起動後は未完了（`Pending`または
  stale `Claimed`）の項目のみを拾う。**ただしこれは再開可能性の話であり、多重実行防止
  （Claim+フェンシング）とは別の関心事である**。再開可能なだけでは複数インスタンスの
  同時実行を防げない。

### 4. Accuracy判定は新設の拡張点、既定は正規化完全一致

`EvaluationRule.evaluate(execution: PromptExecutionSummary): BigDecimal?`は1回の実行の
メタデータ（usage/latency等）のみを受け取る形であり、期待出力との比較や複数回実行の比較を
表現できない。既存インターフェースへの機能追加ではなく、新しい拡張点
`BenchmarkScoringRule`（`promptengine.domain.benchmark`）を設ける。Plugin実装のパッケージは
既存の規約（ADR-0003）に倣い`promptengine.plugin.benchmark.<name>`とする。

```kotlin
interface BenchmarkScoringRule {
    val metricType: String  // "Accuracy" 等
    fun score(actualOutputs: List<String>, expectedOutput: String?): BigDecimal?
}
```

- M2既定実装: 正規化完全一致（trim + 大文字小文字を無視した比較）。`actualOutputs`
  （N回実行の結果）のうち`expectedOutput`と一致する件数の割合をスコアとする。
- 差替例（M2スコープ外、§16表に記載する差替例と同一）: 構造的一致（JSON キー単位の比較）、
  意味的類似（埋め込みベクトル距離）。

### 5. Consistency/Determinismのコストと実装

- **N（試行回数）**: Benchmark作成時に指定可能、既定値は**3**。3指標すべてが同じN回実行
  セットを共有するため（決定3）、Nを増やしても指標数分の掛け算にはならない。
- **Consistency既定実装**: N回の出力を正規化した上で、最頻出の正規化済み文字列と一致する
  件数の割合（Accuracyの正規化一致と同じ思想を「期待出力」ではなく「他の実行結果同士」に
  適用する）。埋め込み類似度は差替例として§16に記載する（M2スコープ外）。
- **Determinism既定実装**: N回の出力のうち、最初の出力とバイト完全一致する件数の割合。
  ただし意味を持つのは`temperature=0`のときのみである。Determinismを要求した
  Benchmarkは、そのTargetのN回実行を強制的に`temperature=0`で行う（利用者が別の値を
  指定してDeterminismも要求した場合はバリデーションエラーとする。曖昧な結果を返さない）。
- **`temperature`の置き場所**（設計書§2.9`RenderedPrompt.modelHints`の確認結果、指摘B）:
  `ExecutionPolicy`はtimeout/retry/parseRepairという**実行の運用方針**を表す型であり、
  temperatureのような**生成パラメータ**を混在させると、将来`top_p`/`seed`等が増えるたびに
  雑多な入れ物になる。設計書§2.9は`modelHints`を「APAP/Provider方言吸収に関わる情報」として
  P7以降に追加検討するとしており（ADR-0013決定6）、M2の今、APAP連携が具体化した時点で
  まさにその契機に当たる。よって`RenderedPrompt`に`modelHints: ModelHints?`
  （`ModelHints(val temperature: Double? = null)`、将来`top_p`/`seed`等を追加できる器）を
  新設する。`ExecutionAdapter.execute(prompt: RenderedPrompt, policy: ExecutionPolicy)`の
  シグネチャは変更しない（ADR-0014決定1が言う「Provider固有表現への変換はAdapter実装の
  内部関心事」の原則に従い、`modelHints`は`RenderedPrompt`経由でAdapter実装へ渡る）。
  - **`renderHash`には含めない。** §2.9の決定性契約は「同一（AST, Bindings, ModelProfile,
    EngineVersion）→ バイト同一出力」であり、temperatureはこの4つに含まれない。
    temperatureはRenderステップの出力バイト（`messages`の内容）に一切影響しない、
    実行時のサンプリングパラメータである。含めてしまうと (a) renderHashの意味
    （「Renderステップの出力そのもののフィンガープリント」）が壊れる、(b)
    `PromptCache`（renderHashをキーの一部に使う）が、レンダリング結果が同一であるにも
    関わらずtemperature違いだけでキャッシュミスするようになる。ADR-0013決定1が
    `outputSchemaRef`をハッシュ入力から除外した理由（効果が既に`messages`本文に
    現れているため二重に入力しない）と同じ考え方で、temperatureは「そもそも
    render出力に影響しない値」として除外する。
  - この変更（`RenderedPrompt`/`RenderHashCalculator`/`PipelineRequest`への`modelHints`
    受け渡し）はフェーズ(c)（Consistency/Determinism実装）で行う。フェーズ(a)では
    決定のみ記録し、コードは変更しない（使われない箇所に先回りしてフィールドを
    追加しない）。
  - **実行時のtemperatureは実行記録側（`execution_logs`または`PromptExecuted`のpayload）に
    残す。** `renderHash`から除外する以上、後からその実行が実際にどのtemperatureで
    行われたかを知る手段が無いと、Determinism（「temperature=0でのバイト一致率」）の
    測定結果を事後に検証できなくなる。フェーズ(c)で`temperature`を実装する際、
    `execution_logs`へのカラム追加（またはevent payloadへのフィールド追加）を
    スキーマ変更として扱い、これまでと同じくADRと設計書§12の両方を更新すること
    （フェーズ(a)/(b)のスキーマ追加と同じ手順を踏む）。
- **Fakeアダプタ前提でよいか**: 単体テスト・統合テストは`FakeExecutionAdapter`で行う。
  ただし現状の`FakeExecutionAdapter`は呼出ごとに固定の1レスポンスしか返せず
  （`FakeExecutionScenario`はシナリオ単位で1つの応答を持つ）、Consistencyが「一致率が
  下がるケース」を検知できることをテストできない。フェーズ(c)で新しい
  `FakeExecutionScenario`（呼出順に複数レスポンスを巡回するバリアント）を追加する。
  実プロバイダでの実行は利用者の判断であり、コード側で制限しない。
- **事前コスト見積り**: 専用エンドポイントは設けない。`POST .../benchmarks`のレスポンス
  （201）に`estimatedExecutionCount`（`datasetSize × targetCount × n`）を含める。
  実行前に同期計算できる値であり、追加のAPI面を増やさずに可視化できる。

### 6. 評価対象は複数Versionの並列比較（Variantとは別概念）

Benchmarkは1つのPromptの複数Version（`semVer`のリスト）を対象にできる。各対象を
`BenchmarkTarget`（`target_id`, `benchmark_id` FK, `version_id` FK）と呼び、
`Variant`（`weightPct`を持つ）とは型を分離する。トラフィック配分という概念が
そもそも存在しないため、混同を防ぐために別の型にする。

各Targetは独立してデータセットに対して採点され、結果は並べて提示する。M2では
`PromotionService`のような自動判定・自動選出は行わない（ADR-0034決定5が
「自動昇格はしない」としたのと同じ理由で、統計的比較の自動化はスコープ外とする）。
Target間の統計的有意差検定（Welch's t-test）は`PromotionService`の計算ロジックを
将来再利用できる余地を残すが、M2では実装しない。

## テーブル定義

```sql
CREATE TABLE golden_datasets (
    dataset_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    prompt_id UUID NOT NULL REFERENCES prompts (prompt_id),
    name VARCHAR NOT NULL,
    description VARCHAR,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE golden_dataset_items (
    item_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dataset_id UUID NOT NULL REFERENCES golden_datasets (dataset_id),
    parameters JSONB NOT NULL,
    context JSONB NOT NULL,
    expected_output TEXT,
    metadata JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 以下4テーブルはフェーズ(b)/(c)で作成する（本ADRはスキーマを確定するのみ）。
CREATE TABLE benchmarks (
    benchmark_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    prompt_id UUID NOT NULL REFERENCES prompts (prompt_id),
    dataset_id UUID NOT NULL REFERENCES golden_datasets (dataset_id),
    n_repetitions INTEGER NOT NULL,
    status VARCHAR NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ
);

CREATE TABLE benchmark_targets (
    target_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    benchmark_id UUID NOT NULL REFERENCES benchmarks (benchmark_id),
    version_id UUID NOT NULL REFERENCES prompt_versions (version_id)
);

CREATE TABLE benchmark_metrics (
    benchmark_id UUID NOT NULL REFERENCES benchmarks (benchmark_id),
    metric_type VARCHAR NOT NULL,
    PRIMARY KEY (benchmark_id, metric_type)
);

CREATE TABLE benchmark_item_results (
    result_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    target_id UUID NOT NULL REFERENCES benchmark_targets (target_id),
    item_id UUID NOT NULL REFERENCES golden_dataset_items (item_id),
    status VARCHAR NOT NULL,
    claimed_at TIMESTAMPTZ,
    claimed_by VARCHAR,
    accuracy_score DECIMAL,
    consistency_score DECIMAL,
    determinism_score DECIMAL,
    error_message VARCHAR,
    completed_at TIMESTAMPTZ,
    UNIQUE (target_id, item_id)
);
```

## §16 拡張ポイント表への追加

| # | 拡張ポイント | Interface | 既定実装 | 差替例 |
|---|---|---|---|---|
| 15 | Benchmark Scoring Rule | BenchmarkScoringRule | 正規化完全一致 | 構造的一致（JSONキー単位）、意味的類似（埋め込み距離） |

## フェーズ分割

規模がADR-0034より大きい（新規Aggregate + 新規データセットスキーマ + 新規非同期実行基盤 +
新規拡張点 + 新規ドメインフィールド）ため、単一PRにせず4フェーズに分割する。

- **(a) 本ADR + Golden Datasetのドメイン/永続化**: `GoldenDataset`/`GoldenDatasetItem`、
  `GoldenDatasetRepository`、`JdbcGoldenDatasetRepository`、マイグレーション（`golden_datasets`/
  `golden_dataset_items`のみ）。REST APIは含めない。
- **(b) Benchmark Aggregate + Accuracy**: `Benchmark`/`BenchmarkTarget`、
  `BenchmarkScoringRule`拡張点と既定実装（正規化完全一致）、`benchmarks`/`benchmark_targets`/
  `benchmark_metrics`テーブル。
- **(c) 非同期実行 + Consistency/Determinism**: `benchmark_item_results`テーブル・Claim/
  フェンシングワーカー、`ModelHints`/`RenderedPrompt`拡張、Consistency/Determinism既定実装、
  `FakeExecutionScenario`の複数応答バリアント。
- **(d) REST API + 認可テスト**: `POST/GET .../benchmarks`、`POST .../benchmarks/{id}/cancel`、
  `POST/GET .../datasets`。ADR確定済みのため§13.1へ行を追加する。新設ハンドラは
  Issue #115のArchUnitルールにより`@PreAuthorize`必須が機械的に強制される。

各フェーズはそれぞれ独立してビルド・テストが通る状態で完結させる。

## 影響

- `docs/PromptEngine_設計書.md` §12（`experiments.type`コメント修正、新規ER追加）・§16
  （拡張点表への行追加）を本ADRと同時に更新する。§2.9（`modelHints`の記述）・§13.1
  （エンドポイント表）はフェーズ(c)/(d)で該当コードと同時に更新する。
- `ExperimentType` enumへの変更は無し（`BENCHMARK`を追加しない）。
- 既存の`EvaluationRule`/`EvaluationEngine`/`EvaluationStage`/`evaluation_records`テーブルへの
  変更は無し。Benchmarkの結果は`benchmark_item_results`に独立して記録する。

## 却下した代替案

- **Experiment Aggregateを共有し`type`で不変条件を分岐**: §12のER図コメントが示唆する形。
  却下理由は決定1に記載の通り。`variants`テーブルの`version_id`/`weight_pct`が
  `NOT NULL`である以上、Variantを持たないBenchmark行はスキーマレベルで無理が生じる。
- **Benchmark単位でのClaim**: 決定3参照。長時間ロック保持とheartbeatの複雑さを避けるため
  項目単位を選んだ。
- **Golden DatasetをObject Storageに保持**: このリポジトリに該当インフラが無く、M2で
  新規に導入する理由がない。
- **temperatureをExecutionPolicyに追加**: 運用方針（timeout/retry）と生成パラメータの
  混在を避けるため却下（指摘B）。
