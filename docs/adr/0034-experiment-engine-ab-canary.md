# ADR-0034: Experiment Engine（A/Bテスト・Canary）を実装する

## ステータス

Accepted

## コンテキスト

FR-015（設計書§1.8）はA/Bテスト・Canary・Benchmark・トラフィック分割・統計判定を要求する。
本ADRはそのうちA/Bテスト・Canary（オンライン実験）を対象とする。Benchmark（オフライン評価）
は別PR・別ADRで扱う（`docs/prompts/m2-4a.md`）。

設計書は`Experiment`をAggregate Root（§4.3: `Variant(2..n), TrafficPolicy`、不変条件
「配分合計=100%。Running中のVariant削除禁止」）として定義し、`experiments`/`variants`
テーブル（§12）、4イベント（§14: `ExperimentStarted`/`ExperimentStopped`/
`ExperimentWinnerDeclared`/`ExperimentCompleted`）、4エンドポイント（§13.1）、
拡張ポイント#12（`TrafficSplitStrategy`、§16）まで既に定義済みである。`experiments`/
`variants`テーブルは`V1__init.sql`に既に存在する（実装未着手のまま先取りして作成されていた）。
`EventTopic`/`EventTopicResolver`も`pe.experiment`トピックと4イベント名を既に登録済みである。

一方、設計書には以下の緊張関係が存在した。

- §2.5「1 Promptにつき「Published」は同時に1 Version（Experiment中はVariantとして複数配信可）」
- §4.3「Prompt」の不変条件「Published同時1Version」（P1で確立、以降の全ロジックの前提）

加えて、ADR-0024（Stage 1 LoadStageの状態ゲート）は`RENDER_ONLY`/`FULL_EXECUTION`で
`Published`/`Deprecated`以外のVersion参照を`PromptVersionStateNotAllowedException`で
拒否する。Variantが`Approved`状態のVersionを配信対象にする場合、通常のversionRef解決経路
（`LoadStage.resolveVersion`）はこのVariantを構造的に拒否する。

## 決定

### 1. 「Published同時1Version」不変条件は一切緩めない。Experiment解決は完全に別経路とする

`Prompt` Aggregateの不変条件・ADR-0024のゲートを変更しない。Variantが参照する
`PromptVersion`は`Approved`のまま配信する（`Published`へは遷移させない）。

Experiment作成時（`POST /experiments`）、各Variantが参照する`PromptVersion`の状態が
`Approved`/`Published`/`Deprecated`のいずれかであることを検証する（`Draft`/`InReview`
参照は拒否）。

Application層に`ExperimentVariantResolver`を新設し、`PipelineOrchestrator.run()`を
呼ぶ**前**に、対象PromptKeyにRunning中のExperimentが無いかを`ExperimentRepository`へ
問い合わせる。あれば`TrafficSplitStrategy`でVariantを選び、そのVariantが参照する
`PromptVersion`を`PromptRepository`から直接取得する。これは`LoadStage.resolveVersion`
（＝ADR-0024のゲート）を一切通過しない、独立した解決経路である。

**なぜ緩めないか**: 「Published同時1件」は`resolveLatestPublished`の破損検知
（Published 2件以上は`IllegalStateException`）、`archive`の参照ゼロ判定、`rollback`の
セマンティクス等、P1以降のほぼ全ロジックの前提になっている。緩和した場合の影響範囲を
洗い出しきれないためP1の不変条件は変更対象から外し、Experiment側を別経路にすることで
実現する。

### 2. `preResolvedVersion`をExperiment解決専用の型で保護し、実行直前に状態を再検証する

決定1の別経路は、実質的にADR-0024のゲートを**迂回する**経路である。単純に
`PromptVersion`型のフィールドとして公開すると、将来の呼出元が任意の`PromptVersion`
（Draft含む）を渡せてしまい、レビュー・承認を経ないPromptがそのまま実行されうる。

2段構えで防ぐ。

**(a) 構築を型で制限する。** `ExperimentResolvedVersion`（`domain.pipeline`）を新設し、
プライマリコンストラクタを`internal`とする。生成できるのは同一パッケージの
ファクトリのみであり、`ExperimentVariantResolver`はこのファクトリ経由でのみ
インスタンスを得る。`Prompt.restore`が`@PersistenceApi`で守られているのと同じ考え方だが、
今回はドメイン外からの誤用ではなく「意味的に無条件では渡してはいけない値」を型で表現する
ため、`@PersistenceApi`ではなく単純な可視性制御（`internal`コンストラクタ + ファクトリ
関数）を用いる。`PipelineRequest.preResolvedVersion: ExperimentResolvedVersion?`とし、
生の`PromptVersion`を直接渡す経路を型レベルで塞ぐ。

**(b) 解決時に状態を再検証する。** Experiment**作成時**の検証だけでは、実験の実行中に
対象Versionが`archive`された場合を捕まえられない（作成後、Prompt側の状態は独立して
変化しうる）。`ExperimentResolvedVersion`のファクトリ自身が、Variant選択直後に
`PromptRepository`から取得した`PromptVersion`の状態を確認し、`Approved`/`Published`/
`Deprecated`のいずれでもなければ生成を拒否し`ExperimentVersionNotUsableException`を
投げる（`GlobalExceptionHandler`で`VALIDATION_FAILED`、ADR-0024の
`PromptVersionStateNotAllowedException`と同じコードに便乗させる。意味が同じ
「現在の状態では受理できない」であるため）。この場合Experiment解決は失敗し、UseCaseは
Experimentが存在しないかのように通常解決へフォールバックせず、エラーとして呼出元へ返す
（サイレントフォールバックは「どのVariantが使われたか分からない実行」を生み、決定4の
再現性要件と矛盾するため）。

`LoadStage`は`request.preResolvedVersion`が非nullなら、それを展開して
`promptVersion`/`experimentVariantId`を`PipelineContext`へ設定するだけの1分岐を
追加する。既存の`resolveVersion`/`requireUsableState`（ADR-0024のゲート）はこの分岐を
通らない。

### 3. Sticky割当は安定ハッシュ（SHA-256）を用いる

`TrafficPolicy`は sticky key の**パス文字列**（例 `"user.id"`）を持つ。Application層の
`ExperimentVariantResolver`が、Pipeline実行前に既に呼出元から渡されている
`PromptRequest.contextData`（Stage 5のContext解決を待たずに参照可能な生入力）から
このパスで値を読む。値が取得できない呼出は重み付き純粋ランダムへフォールバックする。

割当の安定性は**JDKやKotlinの実装依存のハッシュ（`Any.hashCode()`等）を使わず**、
`SHA-256(experimentId + ":" + stickyKeyValue)`の先頭8バイトを符号無し整数として解釈し、
`% 100`を重み累積区間（各Variantの`weightPct`を先頭から積み上げた区間）に対応付ける方式
を用いる。SHA-256はJVMバージョン・プラットフォームに依存せず出力が決定的であるため、
「同一キー→同一Variant」がプロセス・デプロイをまたいで保証される（`renderHash`が
P6でSHA-256ベースの決定性を採用したのと同じ理由）。割当自体の永続化はしない
（Variant構成が変わらない限りハッシュは決定的に同じ結果を返すため、永続化なしで
安定性を担保できる。Variant構成を変える手段は決定6の`PATCH /experiments/{id}/traffic`
であり、変更後に割当が変わることはCanaryの重み調整として想定通りの挙動）。

アルゴリズムは`TrafficSplitStrategy`の既定実装のKDocに明記し、特定の入力に対する
割当先を固定する回帰テストを置く（本ADR「テスト」節参照）。

### 4. 実験実行の再現性と監査

`PromptExecuted`イベント（`PromptExecutedEvent.Payload`）に`variantId: UUID?`を追加する
（既存フィールドは変更しない）。設計書§2.12は既に「`variant_id`はM1では常に`NULL`
（Experiment未実装のため、`PromptExecuted`がVariantを運ばない）」と、この拡張を
先取りして記述していた。

- `evaluation_records.variant_id`（既存カラム、`V1__init.sql`で定義済み、現在常にNULL）
  を`EvaluationSubscriber`経由の`EvaluationEngineImpl`が`PromptExecutionSummary.variantId`
  から埋める。スキーマ変更不要。
- `execution_logs`に`variant_id UUID REFERENCES variants (variant_id)`（nullable）を
  新設する（`V15__execution_logs_variant.sql`）。§12のER図は`evaluation_records`にしか
  variant_idを持たせていないが、運用者が実行ログから直接「どのVariantが使われたか」を
  追える方が「過去の実行を再現できること」（§2.14監査要件）に直接応える。
  `ExecutionLogSubscriber`が同じ`PromptExecutionSummary.variantId`から埋める。
- `audit_logs.payload`はJSONで`PromptExecuted`のpayloadをそのまま保持するため、
  スキーマ変更なしで`variantId`を含む。

### 5. 統計判定の範囲（M2スコープ）

対象指標はLatency/TokenUsage/Costの3種（M2で実装済みの評価器、Quality系は対象外）。

検定は**Welch's t-test**（等分散を仮定しない2標本検定）を用いる。Variantあたり最小
サンプル数**30件**未満は「判定不能」として返す。

**限界（判定結果を見る人が誤読しないための明記）**: LatencyとCostは一般に右に大きく
歪んだ分布になる（M1の実測でp99がp50の10倍以上、README「性能測定」節参照）。平均の
比較を前提とするt検定は、この形の分布では検出力が下がり外れ値の影響を受けやすい。
判定結果は**保守的に解釈すべき**参考値として提示し、`PromotionService`は判定結果
（p値・効果量・十分なサンプル数か）を`GET /experiments/{id}/results`で提示するのみで、
**自動昇格はしない**。`POST /experiments/{id}/promote`は判定結果によらず人が明示的に
呼ぶ別操作とする。多重検定補正・逐次検定（stopping rule）は未実装であり、
`TrafficSplitStrategy`と同じ拡張ポイント思想を`PromotionService`の統計判定部分にも
適用し、差し替え可能にする（§16-12）。

### 6. CanaryとA/Bの違い、および新規エンドポイント

Aggregate/永続化モデルは共通（`experiments.type = AB | CANARY`）。重みの段階的変更は
自動化しない。人がAPI経由で明示的に行う運用とし、新規エンドポイント
`PATCH /experiments/{id}/traffic`（Running中のVariantの`weightPct`を更新、
スコープ`prompt:publish`）を追加する。設計書§13.1にないエンドポイントの追加のため、
本ADRをもって提案とし、設計書§13.1へ行を追加する。

エラー率閾値による自動停止は設けない（Monitoring連携・自動停止ロジックはフェーズ
肥大化リスクが高いため明確にスコープ外とし、将来課題として本ADRに残す）。停止は
既存の`POST /experiments/{id}/stop`（人手）のみ。

### 7. スコープ命名

Experiment系エンドポイントの認可スコープは、新規の`experiment:*`プレフィックスを
起こさず、既存の`prompt:write`/`prompt:publish`/`prompt:read`を再利用する
（`/prompts/{ns}/{name}/evaluations`が`prompt:read`を再利用するのと同じ扱い。
Experimentは常にPromptに従属するリソースであるため）。

### 8. Variant識別子

`Variant`は`variant_id`（UUID、DB採番）ではなく、Experiment内で一意な`name`
（文字列、例 `"control"`/`"treatment"`）で呼出元・API応答から参照する。`variant_id`
はDB内部の識別子として保持するが、REST API（`POST /experiments`のリクエストボディ、
`GET /experiments/{id}/results`のレスポンス）は`name`を主キーとして扱う
（`review_cases`の`approval_id`が内部識別子でしかないのと同じ扱い、ADR-0032）。

## 影響範囲

- `prompt-engine-domain`:
  - 新設 `domain.experiment`パッケージ: `Experiment`（Aggregate Root）、`Variant`、
    `TrafficPolicy`、`ExperimentStatus`（sealed class: Draft/Running/Stopped/Completed）、
    `ExperimentType`（AB/CANARY）、`ExperimentDomainEvent`（sealed）とその4サブクラス、
    `ExperimentMemento`、`ExperimentRepository`インターフェース、
    `TrafficSplitStrategy`インターフェース、`ExperimentVersionNotUsableException`
  - `domain.pipeline`: `ExperimentResolvedVersion`新設、`PipelineRequest.preResolvedVersion`
    追加、`PipelineContext.experimentVariantId`追加、`PromptExecutedEvent.Payload.variantId`追加
  - `domain.evaluation`: `PromptExecutionSummary.variantId`/`EvaluationRecord.variantId`/
    `ExecutionLogEntry.variantId`追加
- `prompt-engine-core`: `TrafficSplitStrategyImpl`（重み付きランダム+SHA-256 sticky）、
  `PromotionServiceImpl`（Welch's t-test）
- `prompt-engine-application`: `ExperimentVariantResolver`、Experiment系Command Handler
  （Create/Start/Stop/UpdateTraffic/Promote）、既存のRender/Execute UseCaseへの
  `ExperimentVariantResolver`呼び出し追加
- `prompt-engine-infrastructure`: `JdbcExperimentRepository`、`PromptExecutedPayloadCodec`/
  `ExecutionLogSubscriber`/`EvaluationSubscriber`/`JdbcEvaluationRepository`の`variantId`対応、
  `appendExperimentDomainEvents`（`DomainEventAppender.kt`）
- `prompt-engine-interface`: `ExperimentController`新設、`ExperimentDtos.kt`新設
- マイグレーション: `V15__execution_logs_variant.sql`
- 設計書§12（`execution_logs.variant_id`追加）・§13.1（`PATCH /experiments/{id}/traffic`行追加）・
  §14（`PromptExecuted`payload定義に`variantId`追記）・§2.12（variant_id常時NULLの記述を更新）

## テスト

- §4.3不変条件違反テスト（配分合計≠100%、Running中Variant削除）
- `ExperimentResolvedVersion`ファクトリが`Draft`/`InReview`/`Archived`状態のVersionを
  拒否すること（決定2b）
- `TrafficSplitStrategy`のsticky割当が特定の入力（experimentId・stickyKeyValue固定値）に
  対して常に同じVariant名を返すことを固定する回帰テスト（決定3、アルゴリズム変更の検知用）
- トラフィック分割が指定重みに統計的範囲で従うこと
- Experiment実行後、`execution_logs`/`evaluation_records`両方にvariant_idが記録され、
  `evaluation_records`から実行を再現できること
- Experiment停止後は通常のVersion解決（LoadStageの既存経路）に戻ること
- 4イベントが§14の封筒形式で発行されAuditに記録されること
- 認可: 各エンドポイントのスコープ有無による200/403
- `PATCH /experiments/{id}/traffic`の重み更新後、sticky割当が新しい重みに従うこと

## 参照

- [PromptEngine_設計書.md §1.8 / §2.5 / §2.12 / §4.3 / §4.4 / §4.5 / §12 / §13.1 / §14 / §16](../PromptEngine_設計書.md)
- [ADR-0024: LoadStageの固定Version参照にPublished/Deprecated状態ゲートを追加する](0024-load-stage-version-state-gate.md)
- [ADR-0032: ReviewCase Aggregateを実装する](0032-review-case-aggregate.md)（永続化・イベント発行パターンの直接の踏襲元）
- [ADR-0026: Evaluation/Audit Subscriber・DLQ](0026-evaluation-audit-subscribers-dlq.md)（`PromptExecuted`payload拡張の先例）
- `docs/prompts/m2-4a.md`
