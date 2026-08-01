# ADR-0020: Promptメタデータ（name/category/description/tags）をAggregate外で扱う（9a）

## ステータス

Accepted

## コンテキスト

`PATCH /prompts/{key}`（設計書§13.1「メタデータ更新」）はname/category/description/tagsの
更新を想定するが、P1で確定した`Prompt` Aggregate（`key`/`versions`/`rowVersion`のみ、
`PromptMemento`も同型）にはこれらのフィールドが一切ない。`prompts`テーブル（§12）には
列（`name`/`category_id`/`description`、`prompt_tags`経由の`tags`）が存在するが、
`Prompt.init`の不変条件（Published同時1件まで）には一切関与しない、純粋な表示・検索用の
属性である（実際、`EventStorePromptRepository.upsertPrompt`は現状`name`列に
`prompt.key.name`——PromptKeyの末尾セグメント——をそのまま書き込んでおり、
独立した編集可能な値としては永続化されていない）。

## 決定

`Prompt` Aggregate・`PromptMemento`・`PromptRepository`は変更しない（P1の確定事項を
維持する）。name/category/description/tagsは、Aggregateの外側にある独立した値として
新設の`PromptMetadata`（`domain.prompt`）・`PromptMetadataRepository`で扱う。

```kotlin
// domain.prompt
data class PromptMetadata(
    val key: PromptKey,
    val name: String,
    val category: String? = null,
    val description: String? = null,
    val tags: List<String> = emptyList(),
) {
    init { require(name.isNotBlank()) }
}

interface PromptMetadataRepository {
    fun find(key: PromptKey): PromptMetadata?
    fun upsert(metadata: PromptMetadata)
}
```

理由: Aggregateの不変条件と無関係な属性をAggregateへ混ぜると、`Prompt.init`が保証すべき
「状態遷移の正当性」という単一責任が薄まる。P1のAggregate境界確定はドメインルールの
中心的決定であり、REST層の都合（PATCHエンドポイントの存在）だけを理由に事後拡張しない。

### `EventStorePromptRepository`との整合

`upsertPrompt`（既存、P2）は`prompts.name`列に`prompt.key.name`を無条件で書き込む
（Aggregate保存のたびに実行される）。`PromptMetadataRepository.upsert`が別途`name`列を
更新しても、次にAggregateが保存されるタイミングでこの上書きにより値が失われる。
これを避けるため、`JdbcPromptMetadataRepository`（9a）は`name`/`category_id`/
`description`の書き込みを`UPDATE prompts SET name = ..., category_id = ..., description = ...
WHERE prompt_id = ...`という、`row_version`や`state`など`EventStorePromptRepository`が
管理する列に触れない限定的なUPDATE文で行う。両Repositoryが同じ`prompts`テーブルの
異なる列サブセットを独立に書く形になるが、列が重複しない（`name`は両方が触るため
例外的に競合しうる）ため、`PromptCommandService.updateMetadata`（9b）は
`PromptMetadataRepository.upsert`のみを呼び、`Prompt` Aggregate自体の`save`は
呼ばない（メタデータ更新はAggregateの状態遷移を伴わないため、そもそも`Prompt.save`を
呼ぶ理由が無い）契約とすることで、書き込みタイミングの競合を構造的に避ける。

### Command/Query経路

`POST /prompts`（Prompt作成）は`Prompt.create`（初版Draft）と
`PromptMetadataRepository.upsert`の両方を呼ぶ（9b、`PromptCommandService.create`）。
`GET /prompts/{key}`・`GET /prompts`（検索）は`PromptRepository`（Aggregateの状態）と
`PromptMetadataRepository`/`PromptSearchRepository`（ADR-0017、表示属性）の両方を
読み合わせてレスポンスを組み立てる。

`categories`/`tags`/`prompt_tags`テーブル（§12）自体の管理（カテゴリ階層の作成等）は
§13.1のエンドポイント一覧に対応するAPIが無いため、P9では読み取り専用とし、
カテゴリ・タグの新規作成はupsert時に無ければ作成する簡易実装とする。

## 影響範囲

- `prompt-engine-domain`: `domain.prompt.PromptMetadata`/`PromptMetadataRepository`新設
- `prompt-engine-infrastructure`: `JdbcPromptMetadataRepository`新設
  （`prompts.name`/`category_id`/`description`、`tags`/`prompt_tags`への読み書き）
- `prompt-engine-application`（9b）: `PromptCommandService.create`/`updateMetadata`が
  `PromptMetadataRepository`を使用（`Prompt.save`とは独立して呼ぶ）
- 設計書の変更は無し（§12のテーブル定義通りに実装するのみ）

## 参照

- [PromptEngine_設計書.md §4.3 / §12 / §13.1](../PromptEngine_設計書.md)
- [ADR-0016: submit-review/approve/rejectのAPI公開をM2へ見送る（Aggregate外Repositoryパターンの前例）](0016-review-endpoints-deferred-to-m2.md)
- [ADR-0017: REST API Read Modelポート（PromptSearchRepositoryと合わせて使用）](0017-rest-api-read-model-ports.md)
