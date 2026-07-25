# Security Policy

## Reporting a Vulnerability

Prompt Engine（PE）に関するセキュリティ上の脆弱性を発見した場合は、公開のIssueやPull Requestではなく、
リポジトリオーナー（[@io0323](https://github.com/io0323)）に非公開で報告してください。GitHubの
[Private vulnerability reporting](https://github.com/io0323/prompt-engine/security/advisories/new) の利用を推奨します。

報告には以下を含めてください:

- 影響を受けるコンポーネント・バージョン
- 再現手順（可能であれば最小限のPoC）
- 想定される影響範囲

## Scope

本リポジトリは設計フェーズであり、現時点で稼働中の実装はありません。実装開始後は、対象範囲・対応バージョン・
開示ポリシーを本ファイルで更新します。

## Handling Sensitive Data

Prompt Engineは `sensitive=true` として宣言された変数値をログ・エラーメッセージに一切出力しません
（詳細は [設計書](docs/PromptEngine_設計書.md) および [CLAUDE.md](CLAUDE.md) を参照）。この方針に反する挙動を
発見した場合は脆弱性として扱い、上記の手順で報告してください。
