# ADR-0016: submit-review/approve/rejectのAPI公開をM2（ReviewCase Aggregate実装後）へ見送る

## ステータス

Accepted

## コンテキスト

実装ガイド§6.10・設計書§13.1のM1対象リストには`submit-review`/`approve`/`reject`の
3エンドポイントが含まれていた。これらはPrompt Aggregateの`submitForReview`/`approve`/
`reject`メソッドを呼べば状態遷移自体は実行できるが、ADR-0004が確立した設計では、
対応するDomain Event（`PromptReviewRequested`/`PromptApproved`/`PromptRejected`、
設計書§14）の発火元は`ReviewCase` Aggregate（Governanceコンテキスト）であり、
`ReviewCase`自体はGitHub Issue #9でM2スコープと定められている。

事前協議（本セッション）では、`review_cases`/`approvals`テーブルを素朴な
Repositoryで直接読み書きする最小実装でこの3エンドポイントを通す方針も検討したが、
以下の理由により採用しない。

### 却下理由: 監査されない状態遷移を外部から起こせてしまう

`ReviewCase` Aggregateを経由しない実装で3エンドポイントをAPIとして公開すると、
CIAP経由で該当スコープ（`prompt:review`/`prompt:approve`）を持つ任意のクライアントが、
Draft→InReview→Approvedという意思決定プロセス上重要な状態遷移を、対応する
Domain Event発行（＝監査ログの正規の記録経路）を伴わずに実行できてしまう。
`Prompt.submitForReview`/`approve`/`reject`のKDocが明記する「M1の間はこれら3遷移が
監査ログに記録されない」という制約は、P1〜P8時点では外部から到達不能な内部APIの
制約に過ぎなかった。P9でREST APIとして公開すると、この制約が外部攻撃面・
ガバナンス上の実害（誰が何を承認したか監査できない）に転化する。

素朴なRepositoryによる`review_cases`/`approvals`の読み書きを追加しても、これは
Domain Eventを発行しない（ReviewCase Aggregateの責務を代替しない）ため、上記の
監査欠落は解消されない。むしろAPIとして公開可能にすることで、この欠落が実運用で
顕在化するリスクを高める。

## 決定

`submit-review`/`approve`/`reject`の3エンドポイントはP9のスコープに含めない。
`ReviewCase` Aggregate（Governanceコンテキスト、GitHub Issue #9）をM2で実装し、
`PromptReviewRequested`/`PromptApproved`/`PromptRejected`イベントの発行経路が
確立されて初めて、これら3エンドポイントをAPIとして公開する。

実装ガイド§6.10のM1対象エンドポイント一覧から該当3件を除外し、M2対象である旨を
明記する。設計書§13.1の表自体（エンドポイント一覧）は変更しない
（表はM1/M2を区別する列を持たず、「どのフェーズで実装するか」はガイド側の
スコープ管理事項であるため）。GitHub Issue #9に、ReviewCase Aggregate未実装の
影響が監査ログ欠落だけでなく承認系APIの公開可否にも及ぶ旨を追記する。

`review_cases`/`approvals`テーブル（V1マイグレーションで作成済み）は、
ReviewCase Aggregate実装（M2）時にそのまま利用する想定で、P9では一切操作しない。

## 影響範囲

- 実装ガイド§6.10: M1対象エンドポイント一覧から`submit-review`/`approve`/`reject`を
  除外し、M2対象として明記
- GitHub Issue #9: 承認系APIもM2スコープである旨を追記
- P9のCommand一覧（ADR-0017）に`SubmitReviewCommand`/`ApproveCommand`/
  `RejectCommand`は含めない
- `domain.governance`パッケージ（ReviewCase関連の最小実装）は新設しない

## 参照

- [PromptEngine_設計書.md §13.1 / §14](../PromptEngine_設計書.md)
- [実装ガイド §6.10](../PromptEngine_ClaudeCode実装ガイド.md)
- [ADR-0004: 状態遷移に対応するDomain Event](0004-domain-events-for-state-transitions.md)
- GitHub Issue #9（ReviewCase Aggregate本実装、承認系APIもM2スコープである旨を追記）
