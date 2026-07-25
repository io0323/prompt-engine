# ADR-0004: 全状態遷移を監査可能にするため PromptWithdrawn / PromptDiscarded を追加する

## ステータス

Accepted

## コンテキスト

設計書§2.5 のPromptライフサイクル遷移表は8遷移を定義しているが、§14のイベント一覧には
このうち `withdraw`（InReview→Draft）と `discard`（Draft→Archived）に対応するイベントが
存在しない。

§4.1 のBounded Context表により、Review/Approval関連（`submitForReview` → `PromptReviewRequested`、
`approve` → `PromptApproved`、`reject` → `PromptRejected`）はGovernanceコンテキストの
`ReviewCase` Aggregateが発火元であり、Prompt Authoringコンテキストの `Prompt` Aggregateは
これらのイベントを発行しない（`ReviewCase` は本フェーズ未実装）。

一方 `withdraw`（著者自身によるレビュー取り下げ）と `discard`（Draftの破棄）は、
ReviewCaseの承認ワークフローを経由しない、Prompt Aggregate自身が完結して行う操作である。
これらの状態遷移がイベントとして残らないと、遷移表8件のうち2件が監査（Audit）から
漏れることになる。

## 決定

`PromptWithdrawn`（withdraw）・`PromptDiscarded`（discard）を新規のPrompt Aggregate発行
イベントとして追加する。既存イベント名は§14の表記にそのまま従う
（`PromptReviewRequested` / `PromptApproved` / `PromptRejected` はリネームしない。
これらはReviewCase発火のため今回スコープ外）。

Prompt Aggregateの各操作とイベント発行の対応（本ADRおよびADR-0005時点）:

| 操作 | Prompt Aggregateがイベントを発行するか |
|---|---|
| create / newVersion | する（PromptCreated / PromptVersionCreated、既存） |
| submitForReview | しない（ReviewCaseが `PromptReviewRequested` を発行） |
| reject | しない（ReviewCaseが `PromptRejected` を発行） |
| withdraw | する（**新規** `PromptWithdrawn`） |
| approve | しない（ReviewCaseが `PromptApproved` を発行） |
| publish | する（PromptPublished、既存。ADR-0005によりPromptDeprecatedも伴う場合あり） |
| rollback | する（PromptRolledBack、既存） |
| deprecate | する（PromptDeprecated、既存） |
| archive | する（PromptArchived、既存） |
| discard | する（**新規** `PromptDiscarded`） |

submitForReview / reject / approve はPrompt Aggregate内でLifecycleStateの遷移そのものは
実行するが、対応するドメインイベントはReviewCase側の責務であるため、Prompt Aggregateの
戻り値としては発行しない。

設計書§14 のイベント封筒定義（`{eventId, eventType, occurredAt, aggregateType, aggregateId,
actor, traceId, payload}`）は変更しない。

## 影響範囲

- 設計書§14 のイベント一覧表に `PromptWithdrawn` / `PromptDiscarded` の2行を追加
- `prompt-engine-domain` の `promptengine.domain.prompt` パッケージに
  `PromptWithdrawn` / `PromptDiscarded` イベントクラスを追加
- `Prompt` Aggregateの `submitForReview` / `approve` / `reject` はイベントを返さない
  （状態遷移後の `Prompt` のみを返す）設計とする

## 参照

- [PromptEngine_設計書.md §2.5 / §4.1 / §4.6 / §14](../PromptEngine_設計書.md)
- [ADR-0005: publishはPublished中のVersionを自動的にDeprecatedへ遷移させる](0005-publish-supersedes-current-published.md)
