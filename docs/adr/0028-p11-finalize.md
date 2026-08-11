# ADR-0028: M1仕上げ（P11）— NFR-002/NFR-003の扱い、api/worker/admin構成

## ステータス

Accepted

## コンテキスト

P11（M1最終フェーズ）では、Dockerfile/Helm/Golden回帰テスト/README整備に加え、
設計書§1.9のNFR-002（Prompt取得キャッシュヒットp99≤20ms）・NFR-003（Render
p99≤200ms）の検証と、Helm Chartの`api`/`worker`/`admin` 3 Deployment構成を確定する
必要があった。事前の方針提示・承認を経て確定した以下の決定を記録する。

## 決定

### 1. NFR-002の扱い

M1では**検証しない**。`PromptCache`（設計書§16拡張ポイント#9、§3.4でInterface定義済み）が
M1のコードベースに一つも実装されていないため測定不能。無効化契機となるTemplate/Fragmentの
Domain Event発行自体がIssue #15（milestone M2、ADR-0008決定3で意図的に見送り）でブロック
されており、キャッシュをM1へ含めるには (a) Issue #15の解消 (b) `PromptCache`本体の実装
(c) 無効化配線という多段の新規作業が必要で、仕上げフェーズの粒度を超える。

設計書§1.9 NFR-002行に「M1では未検証」と注記し、Issue #77（milestone M2、Issue #15を
前提条件として参照）で追跡する。

### 2. NFR-003の測定方法

- **対象**: `deploy/docker/Dockerfile`でビルドした実イメージを、CPU 1 / メモリ 1Gi の
  リソース制限を付けて起動する（`./gradlew bootRun`ではなく実コンテナで測る。JVMフラグ・
  リソース制約が本番相当になるため）。
- **データ**: `tests/prompt-regression`のGolden Prompt fixtureを測定入力としても再利用する
  （測定用と回帰テスト用のfixtureを二重管理しない）。本番相当サイズ（長いsystemブロック・
  few-shot例複数）のfixture（`04-production-scale-support-agent.prompt`）を測定対象とする。
- **負荷生成**: 新規の外部負荷ツールを導入せず、`curl`ベースの`tools/perf/render_load_test.sh`
  で計測する。`curl -K`（設定ファイルに同一URLを複数回列挙）で1回のcurl呼び出し内の
  HTTP keep-alive接続を再利用し、TCP接続確立コストの上乗せを避ける。並列クライアント数分の
  curlプロセスを並行実行し、各プロセスは自分の接続を使い回す。
- **認証**: `SecurityConfig`の既定JwtDecoderは秘密鍵を保持しない自己署名フォールバックの
  ため、外部から有効なBearerトークンを発行できない（意図的な設計、実運用での誤発行防止）。
  `tools/perf/DevJwks.java`（JDK標準ライブラリのみで完結する使い捨てJWKS+JWT発行ツール）で
  `PE_CIAP_JWKS_URI`を差し替え、ローカル限定の検証用トークンで`/render`を叩く。
- **ウォームアップ**: 5,000リクエスト（単一接続、逐次）。JITのTier1（C1）コンパイルは
  既定`-XX:Tier3InvocationThreshold`前後（数百回）で発生するが、Render経路が呼ぶメソッド群
  （Parse/Compile/Resolve/Render複数クラス）を一通りTier4（C2）まで載せるには経験的に
  数千回規模が必要なため、実測で確認できる安全側の値として5,000を採用した。ウォームアップ末尾
  200件の平均と、本測定先頭200件の平均を突き合わせ、値が近いこと（JIT安定後の定常状態で
  測定が始まっていること）をスクリプト実行のたびに確認する。
- **測定環境の限界**: ローカル開発機での実測であり、CI/本番環境のリソース制約・ネットワーク
  条件とは異なる。CI回帰ゲート化は本ADRのスコープ外とし、必要になった時点で別Issueを起こす。

実測値・測定条件（イメージ、リソース制限、ウォームアップ回数、並列度、データ量）は
README「性能測定」節に記録する。

### 3. api / worker / admin の3 Deployment構成

3つとも同一イメージ（`deploy/docker/Dockerfile`）で構成する。M1時点でAdmin API/BFF
（設計書§2.1図の将来コンポーネント）が未実装であり、api/worker/adminを分ける実装上の
差異を新設するのは時期尚早なため。差異は次の2点のみ:

- **`worker`のみ背景ジョブ（Outbox Relay/Broker購読）を起動する**。新規プロパティ
  `promptengine.scheduler.enabled`（既定`true`）で`OutboxRelayConfig`/`SubscriberConfig`
  Configuration全体を`@ConditionalOnProperty`により丸ごと無効化できるようにし、
  `api`/`admin`は`PE_SCHEDULER_ENABLED=false`で起動する。これによりHTTPスケール
  （`api`のレプリカ数）とバックグラウンド処理スケール（`worker`のレプリカ数）を
  実際に分離できる。中継Bean群は`@Scheduled`ジョブ専用でAPIリクエスト処理経路から
  参照されないため、丸ごと止めても副作用が無い。
- **`admin`はClusterIPのみで公開する**（外部Ingressに含めない）。Admin API/BFF実装が
  無い現状ではapiと同一のエンドポイント集合が動くだけだが、Deployment/Service自体は
  将来のAdmin API実装の受け皿として先行して用意する（`deployment-admin.yaml`の
  annotation `promptengine.io/admin-api-status: not-implemented-in-m1`で明示）。

### 4. actuator health probeの認可設定（副次的に発見・修正）

Helm Chartのliveness/readinessプローブ実装中に、`SecurityConfig`の
`authorize("/actuator/health", permitAll)`が完全一致のみで、Kubernetesの
liveness/readinessプローブが実際に叩く`/actuator/health/liveness`・
`/actuator/health/readiness`（サブパス）には効かず401を返すことが判明した
（本ADRのため実装中に発見。全PodがCrashLoopBackOffする実害のある不具合）。
`/actuator/health/**`へワイルドカード化し、`management.endpoint.health.probes.enabled`を
明示的に有効化して修正した（回帰テスト: `ActuatorHealthProbeSecurityTest`）。

## 参照

- [ADR-0008: Template/Fragment Domain Model（決定3、Domain Event見送り）](0008-template-fragment-domain-model.md)
- [ADR-0016: Review Endpoints Deferred to M2](0016-review-endpoints-deferred-to-m2.md)
- docs/PromptEngine_設計書.md §1.9（NFR-002/NFR-003）・§16（拡張ポイント#9 Cache）
- Issue #15・#77（NFR-002関連）
- `deploy/docker/Dockerfile`・`deploy/helm/prompt-engine/`・`tools/perf/render_load_test.sh`
