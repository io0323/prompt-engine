# ADR-0023: PromptKeyのURLパス表現を`{namespace}/{name}`の2パス変数に分割する

## ステータス

Accepted

## コンテキスト

`PromptKey`は設計上`namespace/name`（スラッシュ区切り）形式が必須である（設計書§4.4）。
P9cで初めて`prompt-engine-interface`のREST APIを実HTTP経由で検証したところ、
`/api/v1/prompts/{key}/versions/{v}/publish`のように`PromptKey`全体を単一のパス変数
`{key}`で受け取る実装（P9cドラフト時点の実装）では、`key`に含まれるスラッシュが
Spring MVCの`PathPatternParser`のセグメント境界と衝突し、`{key}`が最初のセグメント
（`namespace`部分）までしかマッチせず、後続の`/versions/{v}/publish`が404
（`NoResourceFoundException`）になることが判明した。ハンドラを直接呼ぶ既存の単体テストは
`PromptKey`オブジェクトを直接構築するため、この問題を一度も踏んでいなかった
（実HTTPを経由する検証が9cで初めてだったため）。

## 検討した選択肢

### 案A: `{key:.+}`可変長パターン

パス変数の正規表現を`.+`（1文字以上、スラッシュ含む）に変更し、クライアントは
生のスラッシュを含む`key`をそのままURLパスに埋め込む。

却下理由: `.+`は貪欲マッチのため、`/prompts/{key:.+}/versions/{v}/publish`のような
「`key`の後に固定セグメントが続く」パターンでは、`key`が`versions/{v}`まで飲み込もうと
した上でバックトラックする曖昧な解決になり、Spring MVCのPathPatternParser
（Boot 3.x既定）ではこの種の「可変長セグメント+固定サフィックス」の組み合わせの
サポートが不安定（バージョン間で挙動が変わりうる）。17エンドポイント全てで
この曖昧さと付き合うことになり、ルーティング定義の保守性を大きく損なう。

### 案B: `%2F`エンコード必須 + サーバー側でデコード許可

クライアントは`key`内のスラッシュを`%2F`としてURLエンコードして送り、
サーバー側は`server.tomcat.decode-encoded-slash-in-path=true`相当の設定で
エンコード済みスラッシュのデコードを許可した上で、単一の`{key}`パス変数として
受け取る。

却下理由（セキュリティ上の懸念）: エンコード済みスラッシュのデコードを許可する設定は、
一般に「パストラバーサル」「WAF・リバースプロキシのパスベースACLバイパス」の
既知の攻撃ベクトルとして知られる（`%2F`がプロキシ層とアプリケーション層で異なる
タイミング・異なる解釈でデコードされることによるパス解釈の不一致）。本APIの
`{key}`はパストラバーサルの対象になるファイルパスではないため直接的なリスクは
限定的だが、「サーバー全体でエンコード済みスラッシュのデコードを許可する」という
設定はこの1用途のために採用するにはリスクとメリットが見合わない。また、
クライアント全員に「`key`はURLエンコードすること」を徹底させる契約上の負担も生じる。

### 案C（採用）: `{namespace}/{name}`の2パス変数

`PromptKey`が保証する「ちょうど2セグメント」という制約（本ADRで`PromptKey`の
正規表現も`[a-z0-9-]+(/[a-z0-9-]+)+`（1個以上）から`[a-z0-9-]+/[a-z0-9-]+`
（ちょうど2個）へ厳密化した）を、そのままURLの2つの独立したパス変数
`{namespace}`・`{name}`に対応させる。

## 決定

案Cを採用する。

- `PromptKey`の正規表現を`[a-z0-9-]+/[a-z0-9-]+`（ちょうど2セグメント）に厳密化する。
  既存のテストデータ・`docs/dsl/samples/**/*.prompt`・DBマイグレーションに
  3セグメント以上のキーが存在しないことを確認済み。
- `prompt-engine-interface`の全REST APIで、`PromptKey`を含むパスは
  `/api/v1/prompts/{namespace}/{name}/...`とする（`PromptController`・
  `VersionController`・`AliasController`・`DependencyController`・
  `MetricsController`・`PipelineController`の計6Controller、設計書§13.1）。
- `namespace`・`name`から`PromptKey`文字列表現（`"$namespace/$name"`）への復元は
  `promptengine.application.view.DomainValueFactory.promptKeyText`の1箇所に集約し、
  各Controllerで文字列結合を行わない。
- 不正な形式（空文字・大文字・記号等）の検出は新規コードを起こさず、
  `PromptKey`の`init`が投げる既存の`IllegalArgumentException`を
  `GlobalExceptionHandler`の既存ハンドラ（`INVALID_REQUEST`、400）にそのまま
  委ねる。

## 影響範囲

- `promptengine.domain.prompt.PromptKey`: 正規表現を`[a-z0-9-]+(/[a-z0-9-]+)+`から
  `[a-z0-9-]+/[a-z0-9-]+`へ変更（3セグメント以上を拒否する回帰テストを追加）
- `prompt-engine-interface`の6Controller: `@PathVariable key: String`を
  `@PathVariable namespace: String, @PathVariable name: String`に変更
- `promptengine.application.view.DomainValueFactory`: `promptKeyText(namespace, name)`
  を新設
- 設計書§4.4（`PromptKey`の定義）・§13.1（エンドポイント一覧のパス表記）・
  §13.2（Request/Response例のパス）を`{namespace}/{name}`表記に更新
  （レスポンスボディの`promptKey`フィールド自体は従来通り`"support/faq-answer"`の
  ままで変更なし。パスの表現方法のみの変更であり、`PromptKey`という概念・
  その文字列表現には影響しない）
- `NoResourceFoundException`（マッチするエンドポイントが無いURL）を
  `GlobalExceptionHandler`が`INTERNAL_ERROR`(500)に丸めていた不具合も同時に修正
  （新設の`ErrorCodes.NOT_FOUND`、404）。本来は独立した不具合だが、
  この調査と同時に9c初回のE2E確認で発覚したため合わせて記録する。

## 参照

- 設計書§4.4・§13.1・§13.2
- `promptengine.domain.prompt.PromptKey`
- `promptengine.application.view.DomainValueFactory.promptKeyText`
- `promptengine.interfaces.rest.PromptController`のKDoc
