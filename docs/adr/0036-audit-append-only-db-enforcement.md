# ADR-0036: 監査ログ・Event StoreのDB層追記専用強制とロール分離

## ステータス

Accepted

## コンテキスト

GitHub Issue #85（監査ログの追記専用性をDB層で強制し、保持期間設定を実装する）のM2スコープ。
`V1__init.sql`は当初から「`audit_logs`は追記専用ログである」とコメントしていたが、
「アプリケーションDBユーザーへのUPDATE/DELETE権限のREVOKEまでは行わない（運用環境のDBロール
設計に依存するため）」として実装を先送りしていた。以来、`prompt_engine`という単一のDBロールが
アプリ実行時・Flyway migration実行時の両方に使われ続けており、追記専用性はアプリケーション層の
`AuditRepository`インターフェース（`update`/`delete`メソッドを持たない）だけが担保していた。

M2棚卸しでの検証（ユーザー指摘）: `prompt_engine`は自らが`CREATE TABLE`したテーブルの
**所有者**である。PostgreSQLでは所有者はGRANT/REVOKEを迂回でき、自分自身に権限を再GRANTできる。
したがって同一ロールに対して`REVOKE UPDATE, DELETE ON audit_logs FROM prompt_engine`を実行
しても、そのロールの認証情報が漏洩した場合には無力であり、実効性のある強制にはならない。
ロール分離（テーブル所有ロールとアプリ実行時ロールを別にする）が必須という結論に至った。

## 決定

### 1. ロールを2つに分離する

- `prompt_engine_migrator`（新設）: 全テーブルの所有者。Flyway migration専用に接続する。
  DDL全権を持つ。
- `prompt_engine`（既存、権限を変更）: アプリ実行時専用。通常のテーブルには
  `SELECT`/`INSERT`/`UPDATE`/`DELETE`を持つが、`audit_logs`/`domain_events`の2テーブルのみ
  `INSERT`/`SELECT`に絞る（`UPDATE`/`DELETE`を持たない）。所有権を持たないため、
  この制限を自分自身では解除できない。

### 2. 対象範囲は`audit_logs`と`domain_events`の2テーブルに限定する

判断基準は「そのテーブルが本当に追記専用か」。

- `audit_logs`: 監査記録そのもの。追記専用が要件（NFR-006）。
- `domain_events`: Event Store。Event Sourcingの正典であり、書き換えられれば履歴そのものが
  改竄される。`audit_logs`より影響が大きいとも言える。
- `outbox`/`dead_letter_queue`は**対象外**とする。これらは`claimed_by`/`dispatched_at`等の
  状態更新を設計上必要とする作業キューであり、追記専用にはできない
  （`V1__init.sql`のコメントも両テーブルを「追記専用」とは記述していない。既存記述の訂正は不要）。

### 3. Flyway接続の分離

Spring Bootの`spring.flyway.user`/`spring.flyway.password`は、`spring.flyway.url`を
設定しなければ`spring.datasource.url`にフォールバックしつつ、user/passwordのみ独立して
上書きできる（各プロパティが個別にフォールバックする、Spring Boot公式ドキュメント
`how-to/data-initialization.html`「If Flyway-specific properties are missing, the
application will fall back to the main DataSource settings」）。これにより、
`prompt-engine-bootstrap`に新規のDataSource Bean定義を追加することなく、
`application.yml`のプロパティ追加のみでFlywayを`prompt_engine_migrator`として、
アプリ本体を`prompt_engine`として、それぞれ独立に接続させられる。

環境変数`PE_FLYWAY_DATASOURCE_USERNAME`/`PE_FLYWAY_DATASOURCE_PASSWORD`（既定値は
ローカル開発用に`prompt_engine_migrator`/`prompt_engine_migrator`）を新設する。

### 4. 権限付与の方式

Flyway migration（`V20__audit_append_only_enforcement.sql`、`prompt_engine_migrator`として
実行）で以下を行う。

0. `prompt_engine`ロールが存在しなければ`CREATE ROLE prompt_engine LOGIN PASSWORD ...`で作成する
   （`pg_roles`を確認する`DO`ブロックで冪等に）。当初はロール作成を
   `docker-entrypoint-initdb.d`（compose専用の初期化フック）に切り出す設計を検討したが、
   Testcontainers（`ActuatorHealthProbeSecurityTest`等、既存の単一ロールコンテナを使う
   ブートストラップテスト）はこのフックを経由しないため、V20が無条件に前提とする
   `prompt_engine`ロールが存在せずGRANT/REVOKEがエラーになることが実装中に判明した。
   ロール作成をマイグレーション自体に持たせることで、Flywayを実行するあらゆる環境
   （compose/CI/Testcontainers）で外部の初期化スクリプトなしに同じ前提が成立する。
1. `GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO prompt_engine`
   （既存テーブル全てへ通常のCRUD権限を広く付与、`audit_logs`/`domain_events`を含む）
2. `ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ...`（`FOR ROLE`は意図的に省略する。
   PostgreSQLは`FOR ROLE`省略時、対象を常に`CURRENT_USER`（＝この接続で実行しているロール）
   とする。ロール名を`prompt_engine_migrator`と決め打ちしないことで、そのロール名を持たない
   環境（Testcontainersが既定で払い出す任意名のロールでFlywayを実行するテスト等）でも
   本マイグレーションが失敗しない。当初`FOR ROLE prompt_engine_migrator`と決め打ちしていたが、
   `ActuatorHealthProbeSecurityTest`等の既存ブートストラップテストが独自のTestcontainers
   コンテナ（デフォルトロール名）でFlywayを実行しており、`role "prompt_engine_migrator" does
   not exist`で失敗することが実装中に判明し修正した。1は既存テーブル、これは将来のテーブルを
   担う。ALTER DEFAULT PRIVILEGESは将来のCREATE TABLEにのみ効き、既存テーブルには遡及しない
   ため両方が必要）
3. `REVOKE UPDATE, DELETE ON audit_logs, domain_events FROM prompt_engine`
   （2テーブルのみ個別に絞る）

### 5. 環境を問わず同じ構成を再現する（本番限定にしない）

ユーザー指摘: 「動かしていない設定は壊れています」（ArchUnitの空集合・統合テストの0件・
`release.yml`・Redis・ブランチ保護で繰り返し確認済みの教訓）。`@Profile("production")`限定に
すると、ロール分離とその権限設定が一度も検証されないまま本番に出るリスクがある。

- `compose.yaml`: `POSTGRES_USER`を`prompt_engine_migrator`に変更する。`prompt_engine`ロールは
  V20（上記0.）が作成するため、initdb用の別スクリプトは不要。既存の`postgres-data`ボリュームは
  `POSTGRES_USER`/`PASSWORD`がinitdb時にしか反映されないため、この変更を取り込む際は
  ボリュームごと作り直す必要がある（README「ローカル開発の手順」に明記）。
- Testcontainers統合テスト: `AuditAppendOnlyEnforcementIntegrationTest`が専用の
  Postgresコンテナを起動し、Flyway（V20）が同じ2ロール構成を再現したうえで、
  `prompt_engine`ロールで接続してINSERT/SELECTが成功しUPDATE/DELETEが失敗することを、
  実際のSQL例外で検証する。

## 保留: 保持期間の自動削除（本Issueのスコープ外）

保持期間（既定7年、設計書§1.9）超過分の自動削除は本Issueでは実装しない。理由:
`prompt_engine`にDELETE権限を与えない設計にした以上、削除は別ロール／別経路が必要であり、
安易に「削除用の抜け道ロール」を作ると追記専用の強制がなし崩しになる。

**将来実装する場合の第一候補**: `audit_logs`/`domain_events`を月次等の時系列
パーティショニング（`PARTITION BY RANGE (occurred_at)`）にし、保持期間超過分は
**パーティション単位のDROP**で削除する。DROPはDDLであり`prompt_engine_migrator`
（テーブル所有ロール）の権限で実行できるため、`prompt_engine`にDELETE権限を一切与える
必要がない。Row-Level Securityによる条件付きDELETE許可（「`occurred_at`が保持期間より
古い行のみDELETE可」）も選択肢としてはあるが、パーティションDROPの方が単純で、
追記専用の制約を全く緩めずに済むため第一候補とする。この方針をIssue（follow-up、
本ADR参照）に記録し、「保持期間のためにDELETE権限を付けよう」という安易な方向へ
流れることを防ぐ。

## 影響範囲

- `compose.yaml`: `POSTGRES_USER`/`POSTGRES_PASSWORD`を`prompt_engine_migrator`に変更
- `modules/prompt-engine-infrastructure/.../db/migration/V20__audit_append_only_enforcement.sql`
  （新設）: `prompt_engine`ロールの冪等作成 + GRANT/REVOKE本体
- `modules/prompt-engine-bootstrap/src/main/resources/application.yml`:
  `spring.flyway.user`/`password`を追加
- `deploy/helm/prompt-engine/`: `values.yaml`（flywayDatasourceUsername/Password既定値）・
  `templates/secret.yaml`（Secret鍵追加）・`templates/_helpers.tpl`
  （`PE_FLYWAY_DATASOURCE_USERNAME`/`PASSWORD`環境変数追加）
- `tests/integration`: `AuditAppendOnlyEnforcementIntegrationTest`（新設）
- `README.md`: ロール分離とボリューム作り直しの案内を追記
- 設計書§1.9 NFR-006を実装状況に合わせて更新

## 参照

- [PromptEngine_設計書.md §1.9 / §12](../PromptEngine_設計書.md)
- GitHub Issue #85（本ADRで着手）
- GitHub Issue #125（保持期間パーティショニングのfollow-up、milestone M3）
