# ADR-0032: ReviewCase Aggregateを実装し、承認確定はPromptと同一トランザクションで整合させる

## ステータス

Accepted

## コンテキスト

GitHub Issue #9・ADR-0004・ADR-0016 が既に確立したとおり、`submitForReview`/`approve`/`reject`
の3遷移は `Prompt` Aggregate内でLifecycleStateの遷移そのものは実行するが、対応するDomain
Event（`PromptReviewRequested`/`PromptApproved`/`PromptRejected`、設計書§14）は`ReviewCase`
Aggregate（Governanceコンテキスト、設計書§4.1）が発火元と定められている。`ReviewCase`が
未実装だったため、M1の間はこれら3遷移が監査ログに記録されず、対応するREST APIも
非公開のままだった（ADR-0016）。

`Prompt.approve(semVer, approvalCount, requiredApprovalCount)`（`Prompt.kt:210`）は
このADRに先立ってP9c時点で既に実装済みであり、`ReviewCase`への参照ではなく素の`Int`を
引数に取る。これは「`ReviewCase`側の処理が承認数を計算した上で`Prompt.approve(...)`を
同一プロセス内で直接呼ぶ」という設計を前提としたシグネチャであり、非同期のイベント
購読を介して後から`Prompt`を更新する構成を前提としていない。

一方、設計書§2.2は「DDD | 6 Bounded Context、**Aggregate単位のトランザクション**」を
アーキテクチャ方針として明記し、§4.2 Context Mapは Prompt Authoring→Governance を
「承認要求: Customer/Supplier」と位置づける。ReviewCaseと Promptを1つのDBトランザクションで
更新することは、この「Aggregate単位のトランザクション」という原則からの逸脱にあたる。

本ADRは、`ReviewCase` Aggregateの実装にあたり、この逸脱を意図的な例外として採用する
判断と、実装スコープにおけるその他3点の決定（ApprovalPolicyの適用単位・自己承認の扱い・
却下後の再提出フロー）を記録する。

## 決定

### 1. approve確定時はReviewCaseとPromptを同一DBトランザクションで更新する

`submitForReview`/`approve`/`reject`のいずれも、1つのApplication層ハンドラが
`ReviewCaseRepository`と`PromptRepository`の両方の`save()`を1つの`TransactionTemplate.execute`
ブロック内で呼ぶ（既存の`EventStorePromptRepository.save`・`JdbcIdempotentCommandExecutor`と
同じ、`@Transactional`アノテーションではなく`TransactionTemplate`を使う既存方式を踏襲）。

**何を諦めたか**: Aggregate境界の独立性。`ReviewCase`（Governance）と`Prompt`（Prompt
Authoring）は設計書§4.1で別Bounded Contextと定義されており、本来はそれぞれが自身の
トランザクションで完結すべきである。将来Governanceコンテキストを別サービスへ切り出す
場合、本ADRで採用した同一トランザクション構成はそのままでは成立せず、Sagaパターン等への
再設計が必要になる。この負債は本ADRの時点で明示的に許容する。

**例外の範囲**: `ReviewCase`↔`Prompt`の承認ワークフロー（`submitForReview`/`approve`/
`reject`の3操作）に限定する。他のAggregate間で同様の一般的な前例として扱わない。
新たに複数Aggregateにまたがる操作を追加する場合は、本ADRを引用して同じ判断を機械的に
適用するのではなく、都度この判断を再検討すること。

**なぜ結果整合（イベント駆動）を選ばなかったか**:
- `ReviewCase`側が承認確定を記録した直後に、`Prompt`側がまだ`InReview`のままである
  「観測可能な中間状態」が生じる。この中間状態をAPIがどう表現するか（`approve`の
  レスポンスを202にしてポーリングさせるか、200のまま不整合なレスポンスを返すか）は、
  設計書§13.1が`approve`の成功コードを200と定めている以上、202への変更は設計書の
  改訂とADR起票を要する別スコープの変更になる。
- この中間状態の間に`publish`が呼ばれると、`Prompt`側はまだ`Approved`に遷移していない
  ため`publish`はガード（Approved→Published）で失敗する。これ自体は安全側だが、
  「承認は完了しているはずなのにpublishできない」という一見矛盾した挙動をクライアントに
  見せることになり、ガバナンス機能としての信頼性を損なう。
- `ReviewCase`と`Prompt`は同一DB・同一サービスプロセス内にあり、Aggregate分離を要求する
  本来の動機（独立したスケーリング・独立した可用性境界）が現時点で存在しない。この
  条件が変わらない限り、強い整合性を選ぶコストは小さい。

### 2. ApprovalPolicyはグローバル設定、既定1、進行中ReviewCaseには遡及しない

`promptengine.review.required-approvals`（`ModelProfileProperties`と同じ
`@ConfigurationProperties`パターン）としてシステム全体に1つ設定する。Prompt単位・
カテゴリ単位の設定は行わない（設計書§12のスキーマに該当するカラムが無く、追加すると
設計書にない仕様を実装で先取りすることになるため）。

既定値は設計書§2.5の記載どおり1。`review_cases.required_approvals`（設計書§12）は
`submitForReview`時点のグローバル設定値をその場でコピーして保存する列であり、
`ReviewCase`はこの列を保存後の自身の状態としてのみ参照する。そのため、グローバル設定を
後から変更しても、既に作成済みの`ReviewCase`（進行中のレビュー）の必要承認数は変わらない。

### 3. 自己承認（4-eyes）は既定で禁止し、設定で無効化可能にする

`promptengine.review.allow-self-approval`（既定`false`）。作成者と承認者が同一actorの
場合、既定設定では承認を拒否する（ドメイン例外を投げる。null返却で誤魔化さない、
CLAUDE.md規約）。単独運用者は`allow-self-approval=true`を明示的に設定する必要がある。

本機能はIssue #9が指す「承認遷移が監査から欠落している穴」を塞ぐガバナンス機能であり、
ガバナンス目的の機能は安全側を既定にする。単独運用時の摩擦は許容する（意図的な
トレードオフ）。

### 4. reject後の再submitForReviewは新規ReviewCaseを作成する。過去コメントは引き継がない

設計書§12のER図は `prompt_versions ||--o{ review_cases`（0..多）と定義しており、1つの
Versionに対して複数の`ReviewCase`行が存在しうることをスキーマ自体が前提としている
（1対1・1対0..1ではない）。この既存スキーマの形に従い、`reject`でDraftへ戻った後に
再度`submitForReview`した場合は、新規`ReviewCase`（新しい`review_id`）を作成する
（既存行の状態を`Draft`等へ戻して再利用しない）。

過去の`ReviewComment`/`ApprovalRecord`は新しい`ReviewCase`へ引き継がない。旧`ReviewCase`
行は改変せず、監査上の完全な履歴として残す（`AuditRecord`の「追記専用・改変不可」と
同じ思想）。

過去のレビューコメントを参照する手段（レビュー履歴API）は設計書§13.1に定義が無いため、
本ADRのスコープでは追加しない。必要性はGitHub Issue #95として別途記録する
（「参照」節）。

### 5. 副次的に発見した欠陥の修正: VersionConflictExceptionがHTTP 500に落ちる

決定1の実装検証中に、`prompt-engine-infrastructure`の`VersionConflictException`
（楽観ロック衝突、ADR-0006）が`ErrorCodeResolver`/`StageErrorMapper`のどちらからも
型判定されず、`INTERNAL_ERROR`（HTTP 500）にフォールバックしていることが判明した
（`TemplateVersionConflictException`/`FragmentVersionConflictException`も同様）。
`ErrorCodes.kt`・設計書§13.3は`VERSION_CONFLICT`→409を定義済みだが、実際には一度も
到達しない設定miss。

`prompt-engine-application`は`prompt-engine-domain`にのみ依存できる
（`prompt-engine-infrastructure`の例外型を直接importできない）ため、`domain/shared`に
`OptimisticLockConflictException`という抽象的な（永続化技術の詳細を持たない）
マーカー例外を新設し、`VersionConflictException`等の具象クラスがこれを継承する形に
修正する。`ErrorCodeResolver`は`domain/shared`のこの型を判定して`VERSION_CONFLICT`を
返す。具体的な`rowVersion`・`promptKey`等の詳細は引き続き`prompt-engine-infrastructure`
側の具象クラスのみが持つ（ADR-0006が「永続化技術に紐づく例外のためdomainには追加しない」
とした決定は、抽象的なマーカー型を除き維持する）。

本修正は決定1で追加する承認確定APIが楽観ロック衝突を初めてHTTP経由で顕在化させる
ことになるため、本フェーズのスコープ内の修正として扱う。Template/Fragmentの同種APIが
副次的に修正されるのは既存の欠陥修正であり、新機能ではない。

## 影響範囲

- `prompt-engine-domain`: `promptengine.domain.governance`パッケージに`ReviewCase`
  Aggregate・`ReviewComment`・`ApprovalRecord`・`ApprovalPolicy`・
  `PromptReviewRequested`/`PromptApproved`/`PromptRejected`イベント・
  `SelfApprovalNotAllowedException`等を新設。`domain/shared`に
  `OptimisticLockConflictException`を新設
- `prompt-engine-infrastructure`: `VersionConflictException`等3クラスが
  `OptimisticLockConflictException`を継承するよう修正。`ReviewCaseRepository`実装
  （Memento + `@PersistenceApi`パターン、review_cases/approvalsテーブル）
- `prompt-engine-application`: `SubmitReviewHandler`/`ApproveHandler`/`RejectHandler`
  （ReviewCase・Prompt両方のRepositoryを同一トランザクションで呼ぶ）。
  `ErrorCodeResolver`に`VERSION_CONFLICT`判定を追加
- `prompt-engine-interface`: `submit-review`/`approve`/`reject`の3エンドポイントを
  設計書§13.1のとおり公開（スコープ: submit-review=`write`、approve=`approve`、
  reject=`review`）
- `PromptLifecycleSmokeTest`: Repository直叩きの`approveVersionDirectly`を廃し、
  実HTTP経由（作成者・承認者2つの異なるsubjectのJWT）へ置き換え
- ADR-0016のステータス節に本ADRへのsupersede注記を追加（内容は保持）
- GitHub Issue #9をクローズ。レビュー履歴API不在はGitHub Issue #95として記録

## 参照

- [PromptEngine_設計書.md §2.2 / §2.5 / §4.1 / §4.2 / §4.3 / §12 / §13.1 / §14](../PromptEngine_設計書.md)
- [ADR-0004: 状態遷移に対応するDomain Event](0004-domain-events-for-state-transitions.md)
- [ADR-0006: 永続化復元経路（EventStorePromptRepository・楽観ロック）](0006-persistence-restore-path.md)
- [ADR-0016: submit-review/approve/rejectのAPI公開をM2へ見送る](0016-review-endpoints-deferred-to-m2.md)
- GitHub Issue #9（ReviewCase Aggregate本実装、本ADRでクローズ）
- GitHub Issue #95（レビュー履歴の参照API不在）
