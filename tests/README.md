# tests/

モジュール横断のテストスイートを配置するディレクトリ。

- `integration/` — Testcontainersを使ったInfrastructure統合テスト（P2〜）
- `contract/` — springdoc生成のOpenAPIと `api/openapi.yaml` の差分検証（P9〜）
- `prompt-regression/` — Prompt DSLのレンダリング結果に対する回帰テスト（P11〜）

各ディレクトリに `build.gradle.kts` を追加すると、`settings.gradle.kts` が自動検出する。
