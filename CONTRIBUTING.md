# Contributing

## 作業の進め方

着手前に、対応する [設計書](docs/PromptEngine_設計書.md) の節と既存コードを読んでください。設計書と実装が
矛盾する場合は、実装を変えるのではなく、まず矛盾を指摘して確認を取ってください。設計書にない公開APIエンドポイ
ント・イベント・エラーコードを勝手に追加せず、必要な場合は先に `docs/adr/` にADRを起こして提案してください。

## ブランチ命名

作業ブランチは以下のプレフィックスを使用してください。

| プレフィックス | 用途 |
|---|---|
| `feat/<phase>-<slug>` | 新機能・新規実装（例: `feat/p1-prompt-lifecycle`） |
| `fix/<slug>` | 不具合修正 |
| `chore/<slug>` | ビルド・CI・依存関係・雑務 |

`main` ブランチへの直接pushは禁止です。すべての変更はPull Requestを経由してください。

## コミットメッセージ

[Conventional Commits](https://www.conventionalcommits.org/) に従ってください。

```
<type>(<scope>): <subject>

例:
feat(domain): add PromptVersion lifecycle
fix(resolver): correct variable priority order
chore(ci): add CodeQL workflow
```

主な `type`: `feat` / `fix` / `chore` / `docs` / `test` / `refactor`。

## Pull Request

- 1PR = 1フェーズ = レビュー可能な粒度（目安 800行以内）。巨大PRは作らない。
- [PRテンプレート](.github/PULL_REQUEST_TEMPLATE.md) に従い、対応する設計書の節番号を必ず記載する。
- マージは Squash merge のみ。マージ後、作業ブランチは自動削除される。
- マージには以下の status check が全て green である必要がある: `build` / `lint` / `arch-test` / `test`。

## テスト

新規のpublicな振る舞いには必ずテストを書いてください。テストなしのPRは出しません。詳細な規約は
[CLAUDE.md](CLAUDE.md) のテスト規約を参照してください。
