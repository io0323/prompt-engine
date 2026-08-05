# ADR-0022: outputSchemaRefの解決経路をM2へ先送りする

## ステータス

Accepted

## コンテキスト

GitHub Issue #36: DSLの`output: {format, schemaRef}`（設計書§15.1、ADR-0015決定9）
は`PromptVersion`/`CompiledPrompt`に保持されているが、`schemaRef`（例:
`"schemas/faq-answer-v1"`）という文字列から実際の`OutputSchema`
（`promptengine.domain.parsing.OutputSchema`、ADR-0014）を解決する経路が存在しない。

`RenderEngine.render`・`ExecutionEngine.run`はいずれも`OutputSchema?`を直接
引数で受け取る設計（ADR-0013・ADR-0014）であり、`PipelineRequest.outputSchema`
（`domain.pipeline`）として呼出側が明示的に渡す値である。つまり現行の
Pipeline実装は、そもそも`schemaRef`からの自動解決を前提にしていない
（呼出側が構築済みの`OutputSchema`を直接渡すことを前提にしている）。

P9（REST API）実装までにこの経路を決めておく必要があったため、以下を検討した。

1. `SchemaRepository`を新設し、`schemaRef`をキーに`OutputSchema`をDBから解決する
2. M1では解決せず、`schemaRef`をAPIレスポンス中は不透明な文字列のまま返し、
   構造化出力検証が必要なクライアントは`POST /execute`リクエストで`OutputSchema`
   相当を明示的に渡せるようにする

## 決定

案2を採用する。M1では`SchemaRepository`を新設しない。

- `POST /prompts/{key}/render`のレスポンスは、設計書§13.2の例の通り`outputSchemaRef`
  を解決せずそのまま返す（文字列のエコーバックのみ）
- `POST /prompts/{key}/execute`は、Structured Output検証を行いたいクライアントが
  リクエストボディで`outputSchema`（`{id, fields: [{name, type, required}]}`、
  `OutputSchema`のフィールド構成に対応するJSON）を明示的に渡せるようにする。
  渡さない場合は検証なし（`schema=null`で`ExecutionEngine.run`を呼ぶ）
- `PromptVersion.output?.schemaRef`が指す実体の永続化・解決（`SchemaRepository`
  相当のInterfaceとその実装）はM2に先送りする

### 却下理由（案1を採用しない理由）

`SchemaRepository`を新設すると、Template/Fragmentと同様の新Aggregateまたは
設定ストアが必要になり、CRUD API・マイグレーション・権限设計まで一式が
P9cのスコープに追加される。設計書にこの永続化モデルの記述が無く（§4.3
Aggregate一覧・§12 ER図のいずれにも`schemas`テーブルは存在しない）、
ADRを経ずに新テーブル・新Aggregateを設計するのはCLAUDE.mdの手続きに反する。
M1時点で`schemaRef`を解決できなくても、案2の代替経路（呼出側が明示的に
`OutputSchema`を渡す）で構造化出力検証自体は機能するため、M1のブロッカーには
ならない。

## 影響範囲

- `prompt-engine-interface`: `ExecuteRequestDto`に`outputSchema`（任意項目）を追加
- `SchemaRepository`・`domain.schema`パッケージは新設しない
- GitHub Issue #36はクローズし、`SchemaRepository`本実装をM2の新規Issueとして
  追跡する

## 参照

- 設計書§13.2 / §15.1
- ADR-0013（Optimization/Render Engine）・ADR-0014（Execution + Response Parsing）
- GitHub Issue #36 / Issue #32（DSLからのOutputSchema回収、M2）
