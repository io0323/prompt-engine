# ADR-0029: M2-1a 暫定Providerアダプタ — 配置・封じ込め・タイムアウト区別・例外分類・usage欠落の扱い

## ステータス

Accepted

## コンテキスト

設計上PEはAI Providerに直接触れず、実行はAPAP（AI Provider Abstraction Platform）へ委譲する
構成である（設計書§16拡張ポイント#11、`ExecutionAdapter`のKDoc）。しかし本リポジトリの範囲では
APAPは実在しない。#31（実APAP接続）着手までの間、Pipeline全体をFakeではない実プロバイダで
検証できるようにするため、暫定的に特定プロバイダへ直接接続する`ExecutionAdapter`実装
（M2-1a）を追加する必要があった。ドメイン/コア/アプリケーション層は変更しない制約のもとで、
以下5点の方針決定と、それに対する3点の追加要求（A: プロバイダ名封じ込めの機械強制、
B: 例外→ErrorType写像の網羅と安全側原則の明文化、C: usage欠落をエラーにする副作用の抑制）を
実装する。

事前の実測で、ADR-0014のリトライ方針が前提とする「接続タイムアウトと応答タイムアウトを
実装上区別できる」ことを`java.net.http.HttpClient`で確認した（後述決定3）。この過程で、
分類ロジックの分岐順序を誤ると接続タイムアウトが全てREAD_TIMEOUT（リトライ不可）に
化けるという実装上の罠も発見した。

## 決定

### 1. 暫定実装の配置（`prompt-engine-infrastructure`ではなく`plugins/execution-<provider>`）

設計書§16拡張ポイント#11は「既定実装: APAP Adapter」「差替例: テスト用Fake、記録リプレイ」と
定めており、本来この拡張ポイントの主たる実体は`prompt-engine-infrastructure`が想定される
（`ApapExecutionAdapter`という具体名まで§5.8シーケンス図に登場する）。

しかし本アダプタは**拡張ポイントの正規実装ではなく、APAP不在の間だけ存在する暫定物**である。
配置の判断基準を「拡張ポイントとの一貫性」ではなく**「削除可能性」**に置く: 実APAP接続が
実現した時点で、このディレクトリとbootstrapの一時的な`testImplementation`・検証ガードを
合わせて削除できる境界に置くことを優先する。

- `plugins/execution-openai`（Plugin実装、ADR-0003命名規則: `promptengine.plugin.execution.openai`）
  に置く。`prompt-engine-domain`・`prompt-engine-plugin-api`の公開型のみを参照し（ADR-0003規約6）、
  `prompt-engine-bootstrap`のDI配線（`ExecutionConfig`）は本PR（M2-1a）では変更しない
  （実接続はM2-1cのスコープ）。
- 削除対象は`plugins/execution-openai`ディレクトリだけではない。`prompt-engine-bootstrap`の
  `build.gradle.kts`が持つ`testImplementation(project(":plugins:execution-openai"))`
  （決定2でArchUnit検証の対象クラスパスに乗せるために追加したもの）と、
  `ProviderNameContainmentTest`（決定2）自身も同時に削除する必要がある
  （CodeRabbitレビュー指摘: `git rm -r plugins/execution-openai`だけではbootstrap側の配線と
  ガードが残り、ビルドまたはガードが不整合になる）。
- `ExecutionAdapter`のKDoc（`prompt-engine-domain`）は、実APAP接続が`prompt-engine-infrastructure`の
  `ApapExecutionAdapter`として来ることの記述はそのまま残しつつ、それまでの間の暫定実装が
  Plugin実装として置かれる旨を追記する（コメントのみの変更。ドメイン層は規約上、具体的な
  プロバイダ名を知らない・書かないため、`plugins/execution-<provider>`という一般化した
  表記に留める）。

### 2. プロバイダ名封じ込めの機械強制（追加要求A）

ArchUnit（`ArchitectureTest`）の「Plugin実装は...に依存しない」検証は**パッケージ境界**
（PE自身の他レイヤへの依存禁止）のみを見ており、サードパーティライブラリや文字列リテラルの
中身までは検証しない（ADR-0003「ArchUnitとKotlin可視性の役割分担」参照）。したがって
「"openai"という文字列が`plugins/execution-openai`の外へ漏れない」という要求は、ArchUnitの
既存検証の対象外であり、別の機械的検証が必要。

`prompt-engine-bootstrap`の`ProviderNameContainmentTest`を新設し、リポジトリ全体の`.kt`
ソースを`settings.gradle.kts`基準のリポジトリルートから走査し、`plugins/execution-openai/`
以外のファイルに"openai"（大文字小文字を区別しない）という文字列が含まれないことを検証する。
`ArchitectureTest`と同じ「他レイヤに置いてはいけないものを機械的に落とす」ガードの思想を、
ArchUnitが扱わない粒度（文字列の中身）まで押し広げたもの。

`prompt-engine-bootstrap`のビルドには`testImplementation(project(":plugins:execution-openai"))`
のみを追加する（`implementation`ではない）。目的は`ArchitectureTest`のADR-0003規約6検証の
対象クラスパスに本Pluginのクラスを乗せることのみであり、実行時DI配線（`ExecutionConfig`）
には一切影響させない。

### 3. タイムアウト区別可能性の実測確認（実装への反映）

`java.net.http.HttpClient`で以下を実測により確認した（スタンドアロンJavaプログラムでの検証。
接続確立前で止まる場合と、接続確立後に応答が来ない場合とで、投げられる例外の型が異なる）:

| シナリオ | 例外 |
|---|---|
| 接続確立前のタイムアウト（未到達IPへのSYNが応答されない） | `HttpConnectTimeoutException` |
| 接続確立後、応答待機中のタイムアウト（接続成功、応答が来ない） | `HttpTimeoutException`（`HttpConnectTimeoutException`ではない） |
| 接続拒否（listenしているプロセスが無い） | `java.net.ConnectException`（`HttpConnectTimeoutException`ではない） |

これによりADR-0014のリトライ方針の前提（両者を実装上区別できる）は成立することを確認した。

**分岐順序の罠**: `HttpConnectTimeoutException`は`HttpTimeoutException`のサブタイプである。
判定順序を誤り`HttpTimeoutException`を先に判定すると、接続タイムアウトが全て
`READ_TIMEOUT`（リトライ不可）に誤分類され、ADR-0014のリトライ安全性の前提そのものを壊す。
`OpenAiFailureClassifier`はこの順序を最上位で固定し、`OpenAiFailureClassifierTest`の
`接続タイムアウトはHttpTimeoutExceptionの分岐に落ちずCONNECT_TIMEOUTに分類される`が
回帰を検知する。

**実装上の追加対応**: `HttpClient`に明示的な`connectTimeout`（既定5秒、`policy.timeoutMs`より
必ず短い値）を設定しない場合、接続確立の遅延がOS依存の長いタイムアウトまで
`HttpTimeoutException`として観測されてしまい、本来リトライ可能な接続タイムアウトを
`READ_TIMEOUT`として取りこぼす。したがって`OpenAiExecutionAdapter`は`HttpClient`構築時に
必ず`connectTimeout`を明示する。

### 4. 例外→`ExecutionErrorType`の分類原則と表（追加要求B）

**原則**: リクエストが**送信されていないと確実に言える場合のみ**リトライ可能な種別
（`CONNECTION_FAILURE`/`CONNECT_TIMEOUT`）とする。送信されたかどうか判別できない場合は、
二重実行（二重課金）のリスクをリトライで得られる可用性より優先し、安全側で`UNKNOWN`
（リトライ不可）に倒す。

| 例外/状況 | 分類 | 根拠 |
|---|---|---|
| `HttpConnectTimeoutException` | `CONNECT_TIMEOUT` | 接続確立前。未送信と断定できる |
| `HttpTimeoutException`（上記以外） | `READ_TIMEOUT` | 接続確立後の応答待機。実行済み・課金済みの可能性を否定できない |
| `UnknownHostException` | `CONNECTION_FAILURE` | DNS解決失敗。TCP接続すら試みられていない |
| `ConnectException` | `CONNECTION_FAILURE` | TCP接続確立自体の失敗。未送信と断定できる |
| `SSLHandshakeException` | `CONNECTION_FAILURE` | TLSハンドシェイク失敗。ハンドシェイク完了前でありアプリケーション層データは未送信と断定できる |
| 上記以外の`IOException`（例: 再利用中の古いコネクションが書き込み後に切断される、ハンドシェイク後の`SSLException`） | `UNKNOWN` | 送信済みかどうか判別できない。安全側でリトライ不可 |
| `InterruptedException`、その他 | `UNKNOWN` | 同上 |
| HTTPステータス`429` | `RATE_LIMITED` | ADR-0014の表と一致 |
| HTTPステータス`5xx` | `SERVER_ERROR` | 同上 |
| HTTPステータス`429`を除く`4xx` | `CLIENT_ERROR` | 同上 |
| HTTP 200だがJSON構文エラー | `UNKNOWN` | ネットワーク層の分類には該当せず、分類不能として安全側に倒す |

「再利用中の古いコネクションが書き込み後に切断される」ケースを`READ_TIMEOUT`に流用せず、
意味的に正確な`UNKNOWN`とした点に注意（`ExecutionErrorType.UNKNOWN`のKDoc「分類不能。
安全側に倒しリトライ不可として扱う」と一致させた。`READ_TIMEOUT`は文字通りタイムアウトを
意味する名前であり、切断のようなタイムアウトでない事象に流用すると将来の読み手を誤導する）。

分類ロジックは`OpenAiFailureClassifier`（純粋関数、`Throwable -> ExecutionErrorType`）に
集約し、ネットワーク通信を介さない決定的な単体テスト（`OpenAiFailureClassifierTest`）で
全分岐を検証する。`Fault.CONNECTION_RESET_BY_PEER`等、実際のJava HttpClientが本当にどの
例外を投げるかはWireMockによる契約テスト（`OpenAiExecutionAdapterContractTest`）で
実測確認する（推測で済ませない、実装ガイドの一貫した方針）。

`HttpConnectTimeoutException`を実際のネットワーク到達不能を待って再現するテストは、CI環境の
ネットワーク挙動（到達不能アドレスへの応答速度）に依存し不安定・低速になるため意図的に
含めない。分類ロジック自体は純粋関数であり、例外インスタンスを直接構築すれば決定的に
検証できるため、`OpenAiFailureClassifierTest`がその役割を担う。

### 5. usage欠落の扱いと副作用の抑制（追加要求C）

`Usage`（domain、`prompt-engine-domain`）は必須フィールドであり「推定値」「欠落フラグ」を
持たない。domain変更なしの制約（本PRのスコープ外）のもと、HTTP 200応答に`usage`が
欠落・不正な場合に取りうる選択肢は「0トークンで黙って埋める」か「エラーとして扱う」の
2つだが、前者はコスト・使用量分析を静かに破損させるため採用しない。

`usage`欠落・`choices[0].message.content`欠落は、`ExecutionErrorType.UNKNOWN`
（ADR-0014の定義上、既に安全側でリトライ不可）として`ExecutionFailedException`を投げる。
**副作用の抑制**として以下2点を実施する:

1. `cause`に専用の例外型（`OpenAiUsageMissingException`/`OpenAiMissingContentException`/
   `OpenAiMalformedResponseException`）を積み、それぞれ固定の識別可能なメッセージ文字列
   （`openai_usage_missing`等）を持たせる。接続エラー等の「本物のプロバイダ障害」と
   ログのcauseチェーン上で一目で区別できるようにするため。
2. 本Pluginは`prompt-engine-domain`・`prompt-engine-plugin-api`の公開型以外に依存できない
   （ADR-0003規約6）ため、専用メトリクス・構造化ログを本Plugin自身から発行することは
   できない。`cause`の型・メッセージが唯一の識別子となる制約を受け入れる。呼出側
   （現状はまだAPAP実接続していないため到達しないが、`ExecutionCoordinator`
   （`prompt-engine-core`）や`ExecutionLogSubscriber`等、実際に`ExecutionFailedException`を
   捕捉・記録する層）で`cause`をメトリクス化する必要が生じた場合は、domain/core側の
   変更を伴うため別Issueとして起票し、本PRのスコープには含めない。

`ExecutionFailedException(UNKNOWN, ...)`が二重課金防止のため既にリトライ不可であることは、
`OpenAiExecutionAdapterContractTest`の`usageフィールドが欠落したHTTP200はゼロ埋めせず
UNKNOWNとして扱われる`が回帰として固定する。

### 6. プロバイダ選定とスコープ外事項

- プロバイダはOpenAI Chat Completions API互換の形状を選定した（`model`/`messages`/`choices`/
  `usage`という広く採用されている形状であり、将来別プロバイダ用の`plugins/execution-<provider>`
  を追加する際の参考実装としても扱える）。
- **429の`Retry-After`ヘッダは本実装では読み取るが、リトライ層へは伝播しない。**
  `ExecutionAdapter`実装自体はリトライを行わない契約（ADR-0014決定7、`RetryingExecutionAdapter`
  が担う）であり、`ExecutionFailedException`/`BackoffPolicy`のいずれも
  プロバイダ提供の待機時間ヒントを運ぶフィールドを持たない（domain変更なしの制約）。
  したがって`RATE_LIMITED`という分類止まりとし、`Retry-After`の値そのものを利用した
  待機時間の調整は行わない。domain側にヒントを運ぶ手段を追加するかどうかは、実APAP接続
  （#31）時に改めて検討する。
- 実際のプロバイダへの接続・`ExecutionConfig`（`prompt-engine-bootstrap`）のDI配線変更は
  行わない（M2-1cのスコープ）。

**本PR後もproductionプロファイルは起動できない（既知のM1からの継続状態、回帰ではない）**:
`ExecutionConfig.executionAdapter`は本PR後も`FakeExecutionAdapter`を束線したままであり、
同アダプタが持つ「`activeProfiles`に`production`が含まれる場合は起動時エラー」というガード
（P9c導入、`FakeExecutionAdapter`のKDoc参照）により、`prompt-engine-bootstrap`は
productionプロファイルでは引き続き起動できない。`OpenAiExecutionAdapter`を
`ExecutionConfig`へ実際に束線し、この制約を解消するのはM2-1c（実プロバイダへの実接続、
本ADRの範囲外）の作業である。M2-1cが完了するまでこの状態は変わらない。

## 影響範囲

- `plugins/execution-openai`（新規モジュール）: `OpenAiExecutionAdapter`・
  `OpenAiFailureClassifier`・usage/content/malformed応答用の専用例外3種
- `prompt-engine-domain`の`ExecutionAdapter`KDoc: 暫定実装の所在についてのコメント追記
  （振る舞い変更なし）
- `prompt-engine-bootstrap`: `build.gradle.kts`に`testImplementation`追加、
  `ProviderNameContainmentTest`新設。`ExecutionConfig`（DI配線）は変更しない
- `gradle/libs.versions.toml`: WireMock（`org.wiremock:wiremock:3.13.2`、testImplementation限定）

## 参照

- [ADR-0014: Execution Adapter / Output Formatter（P7）のドメイン表現・リトライ方針を確定する](0014-execution-response-parsing.md)
- [ADR-0003: Plugin実装のパッケージ命名規則を確定する](0003-plugin-package-naming.md)
- `docs/prompts/m2-1a.md`（本フェーズのキックオフプロンプト、原文のまま保存）
- 設計書§16拡張ポイント#11、§5.8実行シーケンス
