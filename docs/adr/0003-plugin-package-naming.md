# ADR-0003: Plugin実装のパッケージ命名規則を確定する

## ステータス

Accepted

## コンテキスト

CLAUDE.md「モジュール依存の絶対規約」の6番目の規約は「Plugin実装は
`prompt-engine-plugin-api` と `prompt-engine-domain` の公開型のみを参照する」と定めている。
この規約が指す「Plugin実装」は、`plugins/tokenizer-approx` 等（P3以降に追加される
Gradleサブプロジェクト）のコードを指す。

しかし設計書§3.1（論理構成）・実装ガイド§1.2のいずれにも、この「Plugin実装」自体の
Kotlinパッケージ命名は定義されていなかった。そのため、ArchUnit（`ArchitectureTest`）で
この規約を機械的に検証しようにも、検証対象のパッケージを特定できない状態だった。

## 決定

Plugin実装のルートパッケージを `promptengine.plugin.<category>.<name>` とする。

例:
- `plugins/tokenizer-approx` → `promptengine.plugin.tokenizer.approx`
- `plugins/validator-policy` → `promptengine.plugin.validator.policy`
- `plugins/formatter-json` → `promptengine.plugin.formatter.json`
- `plugins/execution-fake` → `promptengine.plugin.execution.fake`

`promptengine.plugin` 直下（`<category>` を経由しない階層）にクラスを置くことを禁止する。
`promptengine.pluginapi`（SPI定義モジュール）と一文字違いで紛らわしく、誤読・誤importの
原因になるため、常に `<category>.<name>` のサブパッケージ配下に置く。

Plugin実装から参照してよいのは `promptengine.pluginapi..` と `promptengine.domain..` のみとする。

### ArchUnitとKotlin可視性の役割分担

規約6は「公開型のみを参照する」までを求めているが、ArchUnitのパッケージ依存検査は
「どのパッケージに依存するか」は検証できても「参照先の型が公開（public）か非公開
（internal）か」までは区別しない。したがって:

- **ArchUnit**（`ArchitectureTest`）: `promptengine.plugin..` が
  `promptengine.pluginapi..` / `promptengine.domain..` 以外のレイヤ
  （application / interfaces / infrastructure / engine / bootstrap）に依存しないという
  **パッケージ境界**のみを機械的に検証する。
- **Kotlinの `internal` 可視性**: `prompt-engine-domain` / `prompt-engine-plugin-api`
  側で、Plugin実装に公開したくない型を `internal` にすることで、モジュール外
  （Plugin実装を含む）から参照不可能にする。「domainの**公開型のみ**」という
  規約6の後半部分はこちらが担保する。

両者は競合せず、ArchUnitが担わない「公開/非公開」の粒度をKotlinの言語機能側が埋める
役割分担とする。

## 影響範囲

- CLAUDE.mdの規約6にこの命名規則を追記
- `ArchitectureTest` に `promptengine.plugin..` を対象とした依存検証テストを追加
  （P0時点では `plugins/*` にサブプロジェクトが存在しないため `allowEmptyShould(true)`。
  P3で最初のPlugin実装を追加した時点から実効化される）
- 設計書§3.2のディレクトリ構成に、各標準Pluginの想定パッケージ名を注記済み

## 参照

- [PromptEngine_設計書.md §3.1 / §3.2](../PromptEngine_設計書.md)
- [ADR-0002: マルチモジュール構成](0002-multi-module-layout.md)
- `CLAUDE.md` モジュール依存の絶対規約
- `modules/prompt-engine-bootstrap/src/test/kotlin/promptengine/bootstrap/ArchitectureTest.kt`
