# Contributing

## 初回セットアップ

クローン後、最初に1回だけ実行してください。`.kt` を含むコミット時に `ktlintCheck` を
自動実行する pre-commit フックが有効になります。

```bash
git config core.hooksPath .githooks
```

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

## ブランチ作成手順

作業ブランチはローカル `main` から作成してください。

```bash
git switch main && git pull
git switch -c feat/<phase>-<slug>
```

`origin/main` から直接切る場合（`git switch -c <branch> origin/main`）は、gitのデフォルト挙動で
そのブランチの upstream が `origin/main` に設定されます。この状態で `git push` すると、
push先が現在のブランチではなく `origin/main` に解決され、意図せず `main` へpushしようとして
保護ルールに弾かれます。この経路を使う場合は `--no-track` を付けて切るか、作成後に
以下でupstreamを張り直してください。

```bash
git branch --set-upstream-to=origin/<branch> <branch>
```

### push.default

本リポジトリではローカル設定で `push.default=simple` を使用してください（未設定の場合）。

```bash
git config --local push.default simple
```

理由: 既定の `upstream` は、upstream先のブランチ名が現在のブランチ名と異なっていても
その upstream 先へpushしてしまいます。`simple` はブランチ名が一致する場合のみpushを許可し、
一致しなければ安全に失敗するため、上記のような意図しないブランチへのpushを未然に防げます。

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
- マージには以下の status check が全て green である必要がある: `build` / `lint` / `arch-test` / `test` / `contract`。
- `contract`が検証するのは「実装 (springdoc生成のOpenAPI) と `api/openapi.yaml` の一致」および
  PRのベースブランチとの破壊的変更の有無（PRのみ、pushイベントでは実行しない）のみ。契約
  （`api/openapi.yaml`）自体が正しいか（例: クエリパラメータが個別に公開されているか、
  レスポンス形状が設計書§13.2と一致するか）は検証しない。契約内容の正しさは `tests/contract`
  に明示的なアサーションを書いて担保すること。

## テスト

新規のpublicな振る舞いには必ずテストを書いてください。テストなしのPRは出しません。詳細な規約は
[CLAUDE.md](CLAUDE.md) のテスト規約を参照してください。

## `.claude/settings.json` を変更するツール・スキルの利用について

`.claude/settings.json` はチーム共有のcuratedファイルです。許可リストを自動生成・自動追記する
類のツールやAIエージェントのスキル（例: セッション履歴から許可コマンドを推測して追記するもの）を
使う場合は、生成された `git diff` を必ず自分の目でレビューしてからコミットしてください。
セッション固有の使い捨てパスや過度に広いワイルドカードが紛れ込みやすく、レビューを省くと
リポジトリの権限設定が汚染されます。セッション中限りの承認は `.claude/settings.local.json`
（gitignore対象）に留め、共有ファイルには入れないでください。
