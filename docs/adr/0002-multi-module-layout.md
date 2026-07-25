# ADR-0002: リポジトリ物理構成としてGradleマルチモジュールを採用する

## ステータス

Accepted

## コンテキスト

[設計書](../PromptEngine_設計書.md) §3.2（旧版）は、リポジトリの物理構成として
`/src/promptengine/...` という単一ソースツリーを前提とした記載になっていた。

一方、実際の実装（P0スキャフォールド）およびCLAUDE.md「モジュール依存の絶対規約」は、
`modules/prompt-engine-domain` 等、レイヤごとに独立したGradleサブプロジェクトへの分割を
前提としている。単一ソースツリーのままでは、レイヤ間の依存制約（domainは他レイヤに
依存しない、applicationはdomainのみに依存する 等）をコンパイル単位で強制できず、
ArchUnit（実行時のバイトコード解析）だけに頼ることになり、依存の混入をビルド構成レベルで
未然に防げない。

## 決定

リポジトリの物理構成として、レイヤごとに独立したGradleサブプロジェクト
（`modules/prompt-engine-domain` / `-application` / `-core` / `-infrastructure` /
`-interface` / `-plugin-api` / `-bootstrap` / `-testkit`、および `plugins/*`）に分割する
マルチモジュール構成を採用する。各モジュールの `build.gradle.kts` の `dependencies` ブロックが
§3.1の論理レイヤ間依存を表現し、Gradleのプロジェクト依存グラフ自体が許可されない依存を
コンパイルエラーとして検出する。ArchUnit（`prompt-engine-bootstrap` の `ArchitectureTest`）は、
このGradle構成では検出できない依存（フレームワークimport、`build.gradle.kts` の将来的な
誤った変更など）に対する追加の安全網として機能する。

設計書§3.2をこの物理構成に合わせて更新する。§3.1（論理構成）とは概念上矛盾しない
（レイヤ名とモジュール名は1:1で対応する）。

## 影響範囲

- [PromptEngine_設計書.md §3.2](../PromptEngine_設計書.md) を本ADRの決定に合わせて更新済み
- `docs/PEP_ClaudeCode実装ガイド.md` §1.2 のモジュール構成（`pep-*` 命名）と本ADRの
  モジュール構成（`prompt-engine-*` 命名）は、製品名リネームによる命名差異のみで
  構造・依存関係は一致する
- 新規レイヤ・新規モジュールを追加する場合は、本ADRのマルチモジュール構成を前提とすること

## 参照

- [PromptEngine_設計書.md §3.1 / §3.2](../PromptEngine_設計書.md)
- [ADR-0001: Interface層のパッケージ名](0001-interface-package-naming.md)
- `CLAUDE.md` モジュール依存の絶対規約
- `modules/prompt-engine-bootstrap/src/test/kotlin/promptengine/bootstrap/ArchitectureTest.kt`
