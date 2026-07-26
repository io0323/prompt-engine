# ADR-0007: sensitive=trueの変数はリテラルのdefaultを持てない

## ステータス

Accepted

## コンテキスト

P2（ADR-0006）で `prompt_snapshots.state`（Event Storeスナップショット）に
`VariableDefinition.default` をそのまま書き込んでいたところ、`sensitive=true`の
変数がリテラルの`default`を持つケースでは平文の秘匿値がJSONBに永続化されてしまう
問題が見つかった。応急処置として `PromptSnapshotPayload` 側で
`sensitive=true`の場合に`default`を`"***"`へマスクする対応を入れた。

しかしマージ前レビューで、この対応には2つの問題があることが分かった:

1. **マスクの範囲が不完全だった。** `prompt_snapshots`（監査・障害復旧用の
   バックアップ経路）だけをマスクし、`variable_defs`（`findByKey`が読む
   主経路そのもの）には一切マスク処理を入れていなかった。つまり平文の
   秘匿値は主要テーブルには無条件で永続化され続けていた。マスク処理は
   「値が流れる場所ごとに個別に用意する」という性質上、実装者が経路を
   ひとつ見落とすだけで漏洩する。実際に1箇所見落としていた。
2. **そもそも仕様違反だった。** 設計書§2.8は Secret変数を
   「Secret Manager参照名のみDSLに記載」「値はRender直前解決、
   ログ・キャッシュ・Audit全てマスク」と定めており、§15.2の`source: secret`の
   変数例（`apiKeyRef`）には`default`が存在しない。`sensitive=true`の変数が
   リテラルの`default`を持つこと自体が、そもそも設計書が想定していない状態
   だった。

統合テストの往復ケース（`sensitive=true`かつ`default`ありの`VariableDefinition`を
含む）を実際に確認したところ、`findByKey`は`variable_defs.default_value`を
無加工で読み戻すため、`default`は失われるどころか**平文のまま完全に往復していた**
（スナップショット側のマスクだけを見て「マスクできている」と誤認していた）。

比較した2案:

- 案a（採用）: `VariableDefinition`の不変条件として「`sensitive=true`のとき
  `default`は`null`でなければならない」を`init`ブロックで強制する。違反は
  `IllegalArgumentException`（ドメイン例外）。これにより、平文の秘密が
  スナップショットはおろか`variable_defs`本体・将来追加されるいかなる
  永続化/直列化経路にも入り込むこと自体が型として不可能になる。マスク処理は
  一切不要になる。
- 案b: 現行どおり永続化層（今回で言えば`PromptSnapshotPayload`）でマスクする。
  値の流れる先ごとにマスクを個別実装する必要があり、今回のように経路の
  見落としが起きうる。また往復が非可逆になる（`"secret-default"` →
  `"***"`）ため、統合テストが検証する「保存→復元→内容一致」という要件と
  そのままでは両立しない（sensitive変数だけ別扱いの弱い比較にする必要が生じる）。

## 決定

案aを採用する。

- `VariableDefinition`の`init`ブロックに
  `require(!(sensitive && default != null))`を追加する。
- `PromptSnapshotPayload`のマスク処理（`if (sensitive) MASKED_VALUE else
  default`）を削除する。上記の不変条件により`sensitive=true`のとき
  `default`は常に`null`であることが型システムではなく生成時点で保証される
  ため、マスクする対象が構造的に存在しなくなる。マスク済みの`"***"`という
  値をnullの代わりに書き込むことは、実際には秘密が無かった場所に
  「秘密があった痕跡」を残す誤解を招くため、単純に`default`をそのまま
  渡すよう戻す。
- 設計書§15.2に「`source: secret`の変数はSecret Managerの参照名のみを
  保持し、リテラルの`default`を持てない」旨を明記する。
- 統合テストの往復ケースに`sensitive=true`（`default`なし）の変数を含め、
  `sensitive=true`かつ`default`ありの構築が例外を投げることをdomainの
  単体テストで検証する。

## 影響範囲

- `prompt-engine-domain`: `VariableDefinition`に不変条件を追加、
  `VariableDefinitionTest`に2件テスト追加
- `prompt-engine-infrastructure`: `PromptSnapshotPayload`からマスク処理を削除
- 設計書§15.2に「Secret変数はリテラルdefaultを持てない」旨を追記
- `tests/integration`: 往復ケースの`sensitive=true`変数から`default`を除去し、
  代わりに`default`なしで往復することを検証

## 参照

- [PromptEngine_設計書.md §2.8 / §15.2](../PromptEngine_設計書.md)
- [ADR-0006: 永続化復元経路（Memento + @PersistenceApi opt-in）](0006-persistence-restore-path.md)
