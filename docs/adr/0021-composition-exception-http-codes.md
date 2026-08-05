# ADR-0021: Composition関連例外のHTTPコードを設計書§13.3に追加する

## ステータス

Accepted

## コンテキスト

GitHub Issue #20: ADR-0009でCompositionServiceの参照解決基盤を実装したが、
`CompositionException`（`promptengine.domain.composition`）のサブタイプのうち、
以下5種には設計書§13.3のエラーコード表に対応するHTTPコードが定義されていなかった。

- `CompositionDepthExceededException`（解決チェーンの深さ超過）
- `CompositionSizeExceededException`（展開後サイズ超過）
- `DraftReferenceNotAllowedException`（COMPILE_ONLY以外でのDraft参照）
- `MacroRecursionException`（マクロの再帰呼出）
- `NestedPromptNotSupportedException`（Nested Prompt、本フェーズ未実装）

`StageErrorMapper`（P8、`prompt-engine-application`）はこれら5種を全て`INTERNAL_ERROR`
にフォールバックしていた。P9cで`prompt-engine-interface`のREST APIを実装するにあたり、
これらは全てクライアント起因（リクエストされたPromptの構成自体が原因）であり、
5xxとして扱うのは誤りである。

`CompositionException`の他のサブタイプ（`SuperWithoutParentBlockException`・
`DuplicateSuperCallException`・`DuplicateImportAliasException`・
`IncludeRequiredVariableUnresolvedException`・`InvalidVariableSubstitutionException`・
`MacroNotFoundException`）はDSL著者向けの構文エラーであり、Issue #20のスコープに
含まれない。これらは引き続き`INTERNAL_ERROR`にフォールバックする（M1では
Compile-onlyでの検証時にDSL著者が直接目にする想定だが、専用コードの要否は
別Issueで扱う）。

## 決定

既存コードへの便乗が妥当かどうかを検討した結果、新規コードは1つのみ追加し、
残りは既存コードへ便乗させる。

| 例外 | HTTP | code | 理由 |
|---|---|---|---|
| `MacroRecursionException` | 400 | `CIRCULAR_DEPENDENCY`（既存） | マクロ呼出のサイクル検出は、依存関係グラフのサイクル検出（`CircularDependencyException`）とクライアントから見た意味が同一（「参照が循環している」）。便乗が妥当。 |
| `CompositionDepthExceededException` | 400 | `COMPOSITION_LIMIT_EXCEEDED`（新規） | 「設定された上限を超過した」という意味は`CIRCULAR_DEPENDENCY`とも`VALIDATION_FAILED`とも異なる（循環でなくとも正当な深いチェーンが上限に達しうる）。新規コードを起こす。 |
| `CompositionSizeExceededException` | 400 | `COMPOSITION_LIMIT_EXCEEDED`（新規、上記と共用） | 深さ超過とサイズ超過は「Composition解決の設定上限超過」という同一カテゴリのため、2つ目の新規コードを起こさず共用する。 |
| `DraftReferenceNotAllowedException` | 400 | `VALIDATION_FAILED`（既存） | 「リクエストされた構成が現在の状態では受理できない」という点で、既存の`VALIDATION_FAILED`の意味（`details[]`に`rule`/`path`/`severity`を積める汎用構造）に収まる。専用コードを起こすほどクライアントの対処が変わらない（いずれ関連Versionをpublishすれば解消する）。 |
| `NestedPromptNotSupportedException` | 400 | `INVALID_REQUEST`（既存） | 「このAPIバージョンでは構造的に処理できないリクエスト」という`INVALID_REQUEST`の既存の意味に一致する。将来Nested Promptが実装されればこの例外自体が発生しなくなるため、専用コードのライフサイクルを別途管理する必要がない。 |

`GlobalExceptionHandler`（`prompt-engine-interface`）は`StageErrorMapper.errorCodeFor`が
返すコード文字列をそのままHTTPステータスへ写像する（`StageErrorMapper`が
唯一の集約点であるというADR-0015決定4を維持する）。`StageErrorMapper`自身に
上記5種の判定分岐と`COMPOSITION_LIMIT_EXCEEDED`定数を追加する。

## 影響範囲

- 設計書§13.3のエラーコード表に`COMPOSITION_LIMIT_EXCEEDED`（400）を追加
- `StageErrorMapper.errorCodeFor`に5種の判定分岐を追加、`INTERNAL_ERROR`
  フォールバックの対象から除外
- `GlobalExceptionHandler`の400系ハンドラで`COMPOSITION_LIMIT_EXCEEDED`を
  HTTP 400として扱う

## 参照

- 設計書§13.3
- ADR-0009: `docs/adr/0009-composition-service-reference-resolution.md`
- ADR-0015決定4（`StageErrorMapper`が唯一の集約点）
- GitHub Issue #20
