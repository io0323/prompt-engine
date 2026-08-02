# P9b — application層 Command/Query ハンドラ実装プロンプト

作成日: 2026-08-02。合意内容の記録（セッションclear対策）。作業前に `feat/p9b-application-handlers` を最新mainから作成すること。

## 参照

- 設計書 §13.1（エンドポイント一覧）、§2.5（ライフサイクル遷移とガード条件）、§2.2（CQRS）、§14（イベント）
- ADR-0009（ExtendsRef）、ADR-0015（Pipeline Orchestrator、output宣言）、ADR-0016（submit-review/approve/reject M2見送り）、ADR-0017（Read Modelポート）、ADR-0020（PromptMetadata）

## 実装スコープ

- Command ハンドラ: Prompt作成 / メタデータ更新 / Archive / Version作成 / ライフサイクル遷移（publish / rollback / deprecate / archive / discard。submit-review・approve・rejectはM2スコープのため実装しない） / エイリアス設定
- Query ハンドラ: Prompt取得 / 検索 / Version取得 / Diff / 依存関係 / 監査ログ / メトリクス
- Pipeline起動系（compile/render/execute）はP8の`PipelineOrchestrator`を呼ぶ薄いUseCase
- Domain Eventの発行（§14の8フィールド封筒形式）
- 各ハンドラはCommand/Queryオブジェクトを入力とし、§13のAPIと1:1対応

## 実装上の要件

- `prompt-engine-application`は`prompt-engine-domain`のみに依存する（ArchUnitルールを緩めない）
- ハンドラにビジネスルールを書かない。判断はAggregate、ハンドラは「データを集めてAggregateに渡し、結果を永続化してイベントを発行する」だけ

## 方針1: Idempotency-Key

### 前提の訂正

`idempotency_keys`テーブルは9a時点で存在しない。設計書§12のER図にも未定義（要追加）。§13.3にも`IDEMPOTENCY_KEY_CONFLICT`は未定義（要追加）。

### 修正A（操作の性質でモードを分ける）

執筆時点の初期案「`command()`全体を1トランザクションで包む」は、`POST /prompts/{key}/execute`（LLM呼び出しを含み数秒〜数十秒かかる）をDBトランザクションで包んでしまいコネクションプール枯渇を招くため**不採用**。

`promptengine.domain.shared.IdempotentCommandExecutor`を2メソッドに分割する:

```kotlin
interface IdempotentCommandExecutor {
    /** CRUD系。予約→command→完了記録を1トランザクションで行う。 */
    fun <T : Any> executeInTransaction(
        idempotencyKey: String?,
        requestFingerprint: String,
        resultType: Class<T>,
        command: () -> T,
    ): T

    /** 長時間操作（execute等）。短いトランザクションで予約→トランザクション外でoperation実行→短いトランザクションで結果記録。 */
    fun <T : Any> executeLongRunning(
        idempotencyKey: String?,
        requestFingerprint: String,
        resultType: Class<T>,
        operation: () -> T,
    ): T
}
```

- CRUD系ハンドラ（Prompt作成/メタデータ更新/Archive/Version作成/ライフサイクル遷移/エイリアス設定）は`executeInTransaction`
- Pipeline起動系のうち`execute`（Full-execution、APAP呼び出しを含む）は`executeLongRunning`。`compile`/`render`はDB外部I/Oを伴わないため`executeInTransaction`で可

### 2フェーズ（executeLongRunning）

1. 短いトランザクションでキーを予約（`status = IN_PROGRESS`）。同一キー・同一fingerprintの既存行が`COMPLETED`ならその結果をデシリアライズして即返す（再実行しない）
2. トランザクション外で`operation()`を実行
3. 短いトランザクションで結果を記録（`status = COMPLETED`、`result_json`書き込み）

### 同一キーがIN_PROGRESS中の再送

`IdempotencyKeyInProgressException`を投げる（後続のREST層で409にマッピングする想定。Controllerは9bのスコープ外のため、この例外を投げるところまでを9bの責務とする）。

### リクエスト指紋（修正C）

キーだけでなく、リクエストの正規化済みハッシュ（`request_fingerprint`）を`idempotency_keys`に保存する。同一キー・異なるfingerprintの再送は`IdempotencyKeyConflictException`を投げる（§13.3に`IDEMPOTENCY_KEY_CONFLICT`を追加、409）。fingerprintはCommandオブジェクトの正規化済み文字列表現をSHA-256でハッシュ化してapplication層で算出する（JDK標準の`java.security.MessageDigest`のみ使用、domain-onlyの依存制約に抵触しない）。

### idempotency_keysテーブル

```sql
CREATE TABLE idempotency_keys (
    idempotency_key VARCHAR PRIMARY KEY,
    request_fingerprint VARCHAR NOT NULL,
    status VARCHAR NOT NULL, -- IN_PROGRESS | COMPLETED
    result_type VARCHAR,     -- 完全修飾クラス名。COMPLETEDになるまでNULL
    result_json JSON,        -- COMPLETEDになるまでNULL
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ
);
```

設計書§12のER図に本エンティティを追加する。既存の`idempotency_keys`定義があった場合はそちらを正としてこの定義を合わせる方針だったが、実際には§12に既存定義が無かったため、本定義を新規に§12へ追加する。

## 方針2: ガード条件の評価元

| 遷移 | ガード | 評価元 | 備考 |
|---|---|---|---|
| publish | allDependenciesPublished | `DependencyRepository`にsemVer指定オーバーロードを追加し、対象semVerのTEMPLATE依存（`ExtendsRef`由来）についてTemplateRepositoryでPublicationState==Publishedを確認 | `dependencies`テーブルへの書き込み経路が現状無いため、9bでVersion作成コマンド内に書き込みを実装する（後述）。**既知の制約**: 9b時点で書き込むのはextends由来のTEMPLATE依存のみ。import/include（FRAGMENT）およびNested Prompt（PROMPT、Issue #19未実装）由来の依存は対象外。全依存を網羅する完全な循環検出・依存グラフ構築は別スコープとして追跡する |
| archive | referencingClientCount | **修正B: 採用しない代替を明確化**。`execution_logs`は本番コードで一切書き込まれていないことをコード調査で確認済み（`JdbcMetricsRepository`のコメントに明記）。真の参照クライアント数を評価する手段がM1に無いため、Option 2を採用: `force=false`の`archive`は明示的なエラー（`ArchiveRequiresForceException`等）を返し、`force=true`のみ受け付ける。`findInbound`（構造的な依存元Prompt件数）はガード判定に使わず、レスポンスの参考情報としてのみ提供可 |
| rollback | 対象Versionが存在・Deprecated | `PromptRepository.findByKey`で取得したAggregate自身の状態 | 追加実装不要 |
| deprecate / discard | ガードなし | - | 該当なし |

### 設計書への反映

- §2.5のarchive行に「M1では`referencingClientCount`の自動判定手段が無いため`force=true`のみ受け付ける」旨を注記
- 追跡Issueを新規作成し（M1完了後 or M2）、`execution_logs`への書き込み経路実装後に「直近N日間実行なし」を自動ガードとして復活させる計画を記録する

## 方針3: トランザクション境界

- **Command**: `IdempotentCommandExecutor.executeInTransaction`（またはCRUD系以外は`executeLongRunning`の2フェーズ）が単位。ガード評価の読み取りもトランザクション内で行う（`executeInTransaction`の場合）。各Repositoryの`save()`は既存の`TransactionTemplate`実装のままでよく、Spring既定のREQUIRED伝播で外側のトランザクションに自動参加する（既存Repository実装の変更不要）
- **Query**: トランザクション境界なし。9aのRead Modelポート（`PromptSearchRepository`等）を直接呼ぶ

## #21対応（Version作成コマンド）

`promptengine.domain.template.ExtendsFieldResolver`をdomainポートとして新設する（`CompositionService`と同じパターン: domainがInterfaceを持ち、core実装（`PromptDslParser` + `ExtendsFieldMapper`をラップ）をbootstrapがDI配線）。

```kotlin
interface ExtendsFieldResolver {
    fun resolve(source: String): ExtendsRef?
}
```

Version作成コマンドハンドラは呼び出し側から`ExtendsRef`を個別引数として受け取らず、必ずこのポート経由で`content.source`から導出する。「保存された`ExtendsRef` == `content.source`をパースした結果」を検証するテストを追加する。対応完了後、Issue #21をクローズする。

## 完了時の報告事項

- `./gradlew build ktlintCheck detekt test`の結果
- テスト件数
- カバレッジ（行・分岐）
- 分岐監査の結果（3分類）
- ガード条件ごとの評価元一覧（本ドキュメントの表を実装後の実態に合わせて更新）
