# ADR-0005: publish は現在のPublished Versionを自動的にDeprecatedへ遷移させる

## ステータス

Accepted

## コンテキスト

設計書§2.5 の遷移表は `publish`（Approved→Published）と `deprecate`（Published→Deprecated）
を独立した行として定義しており、字面通りに読むと「新Versionを主流にするには、まず
現行PublishedをdeprecateしてからApproved Versionをpublishする」という2段階の運用に見える。

しかし、この2段階運用では「deprecate完了後・publish完了前」の間、
どのVersionも`Published`状態を持たない瞬間が生じる。§2.13は `latest`（Published最新）
をクライアントの実行時参照方式の一つと定めており、この瞬間に `latest` を解決しようとした
クライアントは参照エラーになる。これはPrompt配信の可用性を損なう。

## 決定

`publish` は、対象Versionを`Published`にすると同時に、もし他のVersionが現在`Published`で
あれば、そのVersionをアトミックに`Deprecated`へ遷移させる単一操作とする。
新規のイベント種別は作らず、既存の `PromptDeprecated` を `reason=SUPERSEDED` として発行する
（手動での `deprecate` 呼び出しは `reason=MANUAL`）。`recommendedReplacement` には
自動的に新しくPublishされたVersionを設定する。

このとき `publish` 呼び出し1回につき、次のイベントが発行され得る:

- 他に`Published`Versionが存在しない場合: `PromptPublished` のみ
- 他に`Published`Versionが存在する場合: `PromptDeprecated`（reason=SUPERSEDED）と
  `PromptPublished` の両方（この順）

「Published は同時に1Version」という§4.3の不変条件は、この自動supersede処理により、
`publish` 呼び出しの前後どの時点でも破られない。

設計書§2.5 の `publish` 行に、この自動supersede挙動を注記として追記する。

### §9 状態遷移図との不整合の解消

§9 の状態遷移図には `Deprecated --> Published : reactivate(管理者)` という遷移が
描かれているが、これは§2.5 の遷移表（8遷移）に存在しない遷移であり、
§2.5 と§9 は矛盾していた。本ADRでは §2.5 の遷移表を正とし、§9 の当該行を削除する。

誤って `deprecate` してしまったVersionを復帰させたい場合は、新たに`reactivate`という
専用遷移を設けるのではなく、既存の `rollback`（Published→Published、対象は過去に
Publishedだった＝現在Deprecated状態のVersion）で代替できる。この旨を§2.13に追記する。

## 影響範囲

- 設計書§2.5 の `publish` 行にsupersede挙動の注記を追加
- 設計書§9 の状態遷移図から `Deprecated --> Published : reactivate(管理者)` を削除
- 設計書§2.13 に「誤deprecateからの復帰はrollbackで行う」旨を1行追記
- `PromptDeprecated` イベントのpayloadに `reason`（`MANUAL` / `SUPERSEDED`）を追加
- `Prompt.publish()` の戻り値をイベント1件から、発行され得るイベントのリストに変更

## 参照

- [PromptEngine_設計書.md §2.5 / §2.13 / §4.3 / §9](../PromptEngine_設計書.md)
- [ADR-0004: 全状態遷移を監査可能にするため PromptWithdrawn / PromptDiscarded を追加する](0004-domain-events-for-state-transitions.md)
