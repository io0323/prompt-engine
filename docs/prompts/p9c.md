# P9c — interface層 + bootstrap DI配線 実装プロンプト

作成日: 2026-08-03。合意内容の記録（セッションclear対策）。作業前に `feat/p9c-rest-interface` を最新mainから作成すること。

## 参照

設計書§13（API設計）全体。表に無いエンドポイント・エラーコードを追加しない。

## 最重要: アプリケーションが実際に起動することを保証する

P1から現在までbootstrapのDI配線が未着手であり、このアプリケーションは一度も起動していない。916件のテストが通っていてもSpringコンテキストが立ち上がるかは未検証。

- `prompt-engine-bootstrap`で全モジュールの実装を`@Bean`として結線する（domainのInterfaceに対してcore/infrastructure/pluginsの実装を割り当てる）
- Springコンテキストが起動することを検証するテストを必ず置く（`@SpringBootTest`によるコンテキストロードテスト）
- Testcontainers でPostgreSQLを起動した状態でアプリケーションを立ち上げ、Prompt作成・Version作成・publish・renderをHTTPで検証するE2Eスモークテストを1本以上置く。Approved化はテストフィクスチャ（`PromptRepository`直接操作）で準備する（下記「引き継ぎ事項」参照）
- 本番プロファイルでInMemory実装が選択された場合に起動時エラーとなること（P8のADR-0015で決めた方針）を検証する

## 実装スコープ

- §13.1の表のうちM1対象分のController（Prompt CRUD/Version/diff/lifecycle遷移/compile/render/execute/aliases/dependencies/audit-logs/metrics）※submit-review/approve/reject/experiments系はM2のため実装しない
- DTOは§13.2のJSON例と完全一致させる（フィールド名・ネスト構造）
- GlobalExceptionHandlerで§13.3のHTTP↔code対応表を網羅。P8のStageErrorMapperが出すコードと1対1で対応すること
- Spring Security Resource Server（JWT）+ CiapAuthAdapter。スコープ（prompt:read/write/review/approve/publish/execute/admin, audit:read）を強制
- Idempotency-Keyの受け取りと、9bのIdempotentCommandExecutorへの引き渡し
- ページング（既定20・上限100）、X-Trace-Idの受け取りと伝播
- springdoc-openapiの設定と、生成物をapi/openapi.yamlとして出力するGradleタスク

## Issueの回収

- #20: Composition関連エラー（CIRCULAR_DEPENDENCY等）のHTTPコードを設計書§13.3に追加し、GlobalExceptionHandlerに反映してクローズ
- #36: outputSchemaRefの解決経路。M1では未解決文字列としてそのまま返す方針で問題ないか判断し、その決定をADRに記録したうえでクローズするか、M2へ送るかを決めて報告する

## contract.ymlの作成（P0で立てたIssueの回収）

- `.github/workflows/contract.yml`を作成し、springdoc生成のOpenAPIと`api/openapi.yaml`を突合して差分があれば失敗させる
- 破壊的変更の検出も入れる
- CIがgreenになったら、`gh api`でブランチ保護の`required_status_checks`に`contract`を追加する
- 該当Issue（#13）をクローズする

## テスト要件

- 認可: 各エンドポイントについて「スコープ有り→§13.3/OpenAPI定義通りの成功ステータス（作成系は201等）/スコープ無し403/トークン無し401」を網羅
- §13.3の全エラーコードについて、対応するHTTPステータスが返ることを検証
- Idempotency: 同一キーの再送、同一キー・異なるボディでのIDEMPOTENCY_KEY_CONFLICT、IN_PROGRESS中の再送
- ページング境界（上限超過時の扱い）
- 契約テスト（tests/contract）でapi/openapi.yamlと実装の整合を検証
- 新規追加コードの未カバー分岐を3分類で監査（CLAUDE.mdの標準手順）
- カバレッジはbuildSrcの下限を下回らないこと

## 完了時の報告事項

- `./gradlew build ktlintCheck detekt test`の結果
- テスト件数
- カバレッジ（行・分岐）
- 分岐監査の結果
- contractチェックの有効化結果
- E2Eスモークテストの実行結果

## 引き継ぎ事項（P9bから）

- IdempotentCommandExecutor.executeLongRunningのIN_PROGRESS滞留対策はIssue #50でP10へ送り済み（9cはブロックしない）
- Bootstrap DIはP1〜9a分も含め全く未着手。今回が初のDI配線
- **E2Eスモークテストの`publish`について**: `submit-review`/`approve`/`reject`エンドポイントは
  ADR-0016によりM2スコープ（ReviewCase Aggregate未実装のため）。M1のAPIサーフェスだけでは
  Draft→Approvedへ遷移させる手段が無く、`publish`はApproved状態のVersionにしか実行できない
  ため、E2Eスモークテストは実HTTPだけでは完走できない。Approved状態への遷移のみ
  テストフィクスチャ（`PromptRepository`を直接操作）で先回りし、`publish`以降（`publish`・
  `render`という実装済みエンドポイント自体の動作）は引き続き実HTTPで検証する
  （`PromptLifecycleSmokeTest`のKDoc参照、ADR-0016・GitHub Issue #9）。

**追記（M2-2、CodeRabbitレビュー指摘によりこの節自体は9c当時の記録として保持し、以下を
別項として追記する）**: ReviewCase Aggregateとsubmit-review/approve/rejectを実装し
（ADR-0032、ADR-0016をsupersede）、上記フィクスチャのバイパスは`PromptLifecycleSmokeTest`
から削除した。現在は全工程が実HTTPで完走する。
