# plugins/

標準Plugin（tokenizer, validator, evaluator, formatter）を配置するディレクトリ。
P3以降のフェーズで、`prompt-engine-plugin-api` を実装するGradleサブプロジェクトを
このディレクトリ配下に追加する。`settings.gradle.kts` は `build.gradle.kts` を持つ
サブディレクトリを自動検出するため、追加時の設定変更は不要。
