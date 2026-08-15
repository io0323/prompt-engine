# ADR-0033: Template/Fragment Domain EventとPromptCacheを実装する

## ステータス

Accepted

## コンテキスト

GitHub Issue #15（Template/Fragment Domain Event未実装）・Issue #77（CompiledPromptキャッシュ）
のM2スコープ。ADR-0008は「§14に対応イベントが定義されていない」ことを理由にTemplate/Fragmentの
Domain Event発行を意図的に見送った。設計書§1.9 NFR-002（Prompt取得キャッシュヒット時p99≤20ms）は
Issue #15解消後にIssue #77で実装・検証すると明記済み。本ADRはこの2Issueをまとめて実装するにあたり、
実装前に決定すべき5点（イベント定義・キャッシュキー・無効化の正しさ・Secret境界・複数インスタンス
無効化）と、事前調査で判明した4点の追加事項（COMPILE_ONLYの扱い・TTL具体値・逆依存探索の実装・
Redis実接続検証）を記録する。

調査の過程で、当初想定していなかった3点の事実が判明した。これらは決定の前提を変えるため、
「決定」節の各項目内で根拠として明示する。

1. **`dependencies`テーブルは`from_version_id`が`prompt_versions.version_id`への外部キー制約を
   持ち、Prompt Version以外を"from"側にできない**（`V1__init.sql`）。TemplateやFragment自身が
   依存エッジの発生源になることは、スキーマ上そもそも不可能である。
2. **`CompositionServiceImpl.compile()`が生成する`CompiledPrompt.dependencies`は、extendsチェーン
   全体（`ReferenceResolver.resolveExtendsChain`が`TemplateVersion.extends`を`null`になるまで
   辿る）と、その各階層（Prompt自身の本文＋祖先Template各々の本文）から`FragmentResolver`が
   再帰的に収集した全Fragment参照（Fragment内Fragment includeを含む）を、単一のフラットな
   リストとしてすでに保持している**。つまり「Fragment → Template → Prompt」という間接依存は、
   compile時点で1階層（Prompt起点）に平坦化済みである。
3. **Template/Fragment Aggregateには、現時点でREST公開エンドポイント（設計書§13.1に該当行なし）
   もApplication層のコマンドハンドラも存在しない**。`TemplateRepository`/`FragmentRepository`は
   `PublishHandler`のガード判定（読み取りのみ）と`CompositionServiceImpl`（読み取りのみ）からしか
   参照されておらず、`Template.create/newVersion/publish/archive`を呼ぶ書き込み経路は
   ドメイン単体テスト以外に存在しない。

## 決定

### 1. Template/Fragmentイベントの定義とトピック

Prompt側の命名（過去形`{Aggregate}{動詞過去形}`）に揃え、以下8件を設計書§14に追加する。

| イベント名 | 発火元 | 主な購読先 | 用途 |
|---|---|---|---|
| TemplateCreated | Template Aggregate | Audit | 新規登録の監査 |
| TemplateVersionCreated | Template Aggregate | Audit | 新Version追加の監査 |
| TemplatePublished | Template Aggregate | Cache Invalidator, Audit | 配信切替・キャッシュ無効化 |
| TemplateArchived | Template Aggregate | Cache Invalidator, Audit | 廃止の監査・キャッシュ無効化 |
| FragmentCreated | Fragment Aggregate | Audit | 新規登録の監査 |
| FragmentVersionCreated | Fragment Aggregate | Audit | 新Version追加の監査 |
| FragmentPublished | Fragment Aggregate | Cache Invalidator, Audit | 配信切替・キャッシュ無効化 |
| FragmentArchived | Fragment Aggregate | Cache Invalidator, Audit | 廃止の監査・キャッシュ無効化 |

Template/Fragmentは簡略版3状態（`PublicationState`、Draft→Published→Archived）のみでレビュー
ワークフローを持たないため、`submitForReview`系イベントは対応不要（Promptの6状態ライフサイクル
との非対称はADR-0008が既に確立済み）。

トピックは新設せず既存の`pe.prompt`（`EventTopic.PE_PROMPT`）に載せる。設計書§4.1の
Bounded Context表がTemplate/Fragment/Promptを同一の「Prompt Authoring」コンテキストに
分類しており、Topic粒度をBounded Context単位に揃える既存方針（ADR-0025決定7）と一致するため。

`aggregateId`は`TemplateKey.value`/`FragmentKey.value`（Promptの`key.value`と同じ、業務キー）。
`EventTopicResolver`の閉じた集合（設計書§14の30件限定、ADR-0025決定7）に8件を追加する。

**副次的に発見した欠陥の修正（決定1関連）**: `CacheInvalidationSubscriber`は
`PromptKey(envelope.aggregateId)`でPromptKeyを復元しようとするが、`pe.prompt`を流れる
`domain_events`由来イベントの`envelope.aggregateId`は`DomainEventOutboxSource`のKDocが
明記する通り`prompts.prompt_id`（DB採番のUUID文字列）であり、`PromptKey`の`namespace/name`
形式には一致しない。したがって`PromptPublished`等の実イベントに対して常に
`PromptKey(...)`のパースが失敗し、`runCatching {...}.getOrNull() ?: return`で無音に
無効化をスキップしていた。これはPromptKeyという型がある種の"事実上のUUID文字列"を
誤って受理してしまう話ではなく、正規表現`[a-z0-9-]+/[a-z0-9-]+`に一致しないため確実に
例外化する（UUIDには`/`を含まない）。結果、**M1完成時点からキャッシュ無効化の
イベント購読経路は本番相当のイベント形状に対して一度も成功したことがない**。

修正: `aggregateId`ではなく、`PromptPublished.Payload`が既に持つ`promptKey: String`
フィールド（`envelope.payload`のJSONボディ）から復元する。`PromptExecutedPayloadCodec`と
同じパターン（`ObjectMapper`でJSON解析、欠落・型不一致は例外化してDLQへ）で
`CacheInvalidationPayloadCodec`を新設する。Template/Fragment側もこのコーデック経由で
`templateKey`/`fragmentKey`/`semVer`を読む。

### 2. キャッシュキーの設計

設計書§5.1/§5.2の疑似コード`compiledKey(key, versionRef)`のとおり
`CacheKey(promptKey: PromptKey, versionRef: VersionRef)`とする。

`CompositionMode`はキーに含めない。決定「a」（後述）でCOMPILE_ONLYの結果自体を
キャッシュ対象外とするため、キャッシュに乗る`CompiledPrompt`は常にSTANDARDモードの
結果のみであり、モードをキー次元に持つ意味がない（値が常に固定の次元をキーに含める方が
むしろ「別モードでも別キーだと誤解させる」リスクになる）。

`versionRef`が`Latest`/`Alias`の場合の失効: `PromptCache.invalidateByPrompt(key: PromptKey)`
（設計書§3.4のシグネチャそのまま）は`versionRef`を引数に取らない。そのため実装は
`PromptKey`をprefixとした削除（例: Redisの`SCAN`+`DEL`によるprefixマッチ、またはprefixを
Setで管理し削除時にメンバー一覧からDELする）とし、`Fixed`/`Latest`/`Alias`いずれのキーで
入っていても当該PromptKeyの全エントリを無条件に落とす。「latestが指す先が変わった」を
個別に判定するロジックは持たない（判定を持たないことで判定漏れも起きない）。

### 3. 無効化の正しさ（最重要）

**当初の想定（Fragment/Template/Promptの多階層を`findInbound`で再帰的に辿る）は不要と判明した。**
根拠はコンテキスト節の1・2。`dependencies`テーブルの行は必ず`from`側がPrompt Versionであり、
かつ`CompiledPrompt.dependencies`はextends連鎖・Fragment include連鎖（Fragment内Fragmentを
含む）をコンパイル時点で1階層に平坦化済みである。したがって「あるPromptがTemplate/Fragmentに
依存している」という関係は、そのPromptの`dependencies`行に**直接**現れる。多段階のグラフ探索を
実装すると、実際には`from_version_id`がPrompt以外を指すことがないため2階層目には到達不能な
コードになり、CLAUDE.md「作業の進め方5」が定める分岐監査で「到達不能」に分類されて削除対象に
なる。よって単純な1回の`findInbound`検索のみを実装する。

- **write-path（決定3-c関連の前提修正）**: 現行`CreateVersionHandler`は
  `dependencyEdgesFrom(extends)`（`ExtendsFieldResolver`によるfront matterのみの浅いパース）で
  extends由来のTEMPLATE依存1件のみを書き込み、import/include（FRAGMENT）は一切書き込んでいない
  （`docs/prompts/p9b.md`の既知の欠落）。この欠落を、`CompositionService.compile(key,
  newVersion相当のPromptVersion, CompositionMode.COMPILE_ONLY)`を`CreateVersionHandler`内で
  実行し、その結果の`CompiledPrompt.dependencies`（`TemplateDependency`+`FragmentDependency`の
  平坦化済みフルセット）から`DependencyEdge`を組み立てる方式に置き換えて修正する。
  `COMPILE_ONLY`を使うのはDraft状態の参照を許可するため（ADR-0024）。`requestedRange.toRangeText()`
  を`DependencyEdge.toVersion`に格納する（`dependencyEdgesFrom`が`ExtendsRef.range.toRangeText()`を
  使っていたのと同じ変換）。これにより`dependencyEdgesFrom`と`ExtendsFieldResolver`単体の
  役目は本経路に統合され、`replaceOutbound`へ渡す1回のedges生成が「実際にcompileが使う依存」と
  常に一致する（二重の解決ロジックによるドリフトが構造的に起きない）。
  この変更は「Version作成時に参照先の実在確認を早める」という副作用を持つ（存在しない
  Fragment/Templateを参照するDraftの作成が、以前は許容され初回Load時に失敗していたのに対し、
  今後はVersion作成時点で失敗する）。COMPILE_ONLYはDraft状態の参照は許すが「存在しない参照」は
  許さないため、フェイルファスト化は安全側の変更として扱う。
- **SemVer範囲判定**: `DependencyEdge.toVersion`（`requestedRange.toRangeText()`の保存値）を
  `VersionRange.parse`で復元し、`matches(publishedSemVer)`で判定する（ADR-0009で確立済みの
  唯一の往復変換経路をそのまま再利用、新しい判定ロジックを作らない）。
- **`findInbound`の拡張**: 現行`JdbcDependencyRepository.findInbound`は`to_kind='PROMPT'`固定。
  `to_kind`をパラメータ化し、TEMPLATE/FRAGMENT向けにも使えるようにする
  （`DependencyRepository.findInboundTemplateOrFragment(kind, key)`を追加。既存の
  `findInbound(promptKey): PROMPT専用`はシグネチャ・意味とも変更しない）。
- **漏れがないことの保証**: 「キーが一致すること」ではなく「内容が更新されていること」で
  検証するテスト（後述）に加え、`DependencyKind`の全値（TEMPLATE/FRAGMENT/PROMPT）について
  無効化の到達経路がある旨をアーキテクチャテストで固定する。PROMPT種別（Nested Prompt、
  Issue #19未実装）は経路自体が存在しないため対象外とし、その旨をテストのコメントに明記する
  （存在しない機能の無効化を偽装しない）。

### 4. Secretとキャッシュの境界

`CompiledPrompt`の実フィールド（`body: List<PromptAst>`, `dependencies: List<ResolvedDependency>`,
`variables: List<VariableDefinition>`, `contextRequirements`, `validation`, `output`）を確認した。
`variables`は`VariableDefinition`（宣言）であり、値が束縛された`Value`/`BindingSet`ではない。
Secret変数は§2.8のResolver Chainにより「Render直前」に解決される値であり、`CompiledPrompt`の
生成（Stage 2 Merge、`CompositionService.compile`）はStage 4（Variable解決）・Secret解決より
前段にある。したがって「`CompiledPrompt`はSecretを含み得ない」は次の2層で保証される。

1. **型レベル**: `CompiledPrompt`のいかなるフィールドも、解決済みSecret値を保持できる型
   （`SensitiveValue`または束縛済み`Value`）を持たない。
2. **Pipeline順序レベル**: `PromptCache`の`get`/`put`は`MergeStage`（Stage 2）にのみ実装し、
   Stage 4以降（Variable/Secret解決）のコードから`PromptCache`を参照しない
   （参照する経路を作らない、という運用上の制約）。

defense-in-depthとして、`CompiledPrompt`のフィールド型を反射で検査し「`SensitiveValue`型・
束縛済み`Value`型を持たない」ことを固定するアーキテクチャテストを追加する（将来Render結果
キャッシュを検討する際に、この境界がテストで機械的に守られる）。

### 5. 複数インスタンスでの無効化

`PromptCache.put(key, item, ttl: Duration)`の`ttl`を、イベント無効化が届く前に読まれる窓の
上限として使う。**既定値は30秒**とする。

根拠: この値を新規に決めるのではなく、本システムが既に持つ「イベント配信の最大遅延」の
確立値に合わせる。`OutboxRelayProperties.claimTimeoutSeconds`（既定30秒）は「クレームした
プロセスがクラッシュしたとみなすまでの秒数」であり、outbox中継が正常時（`pollIntervalMs`
既定750ms）ではなく最悪時にとり得る遅延の上限として本システムに既に存在する数値である。
TTLをこれと同じ30秒に揃えることで、「キャッシュの古さの上限」を「イベント配信の古さの上限」
より短くしてしまう（無効化イベントより先にTTLで消える分には無害だが、その逆＝TTLが
outboxの最悪ケースより長く、無効化イベントがまだ届いていないのにTTLでも消えない、という
状態を作らない）新しい数値を導入せずに済む。

NFR-009との関係: NFR-009（「Read Model/検索Indexは結果整合、遅延≤5s」）の対象は文言上
「Read Model/検索Index」であり、`PromptCache`はどちらでもない（Read Modelは検索・一覧用の
非正規化ビュー、`PromptCache`はCompile結果の再利用キャッシュ）。したがって`PromptCache`の
TTLをNFR-009の対象として扱わない。ただし本システムの通常時イベント配信遅延（`pollIntervalMs`
750ms）はNFR-009の5s目標を大きく下回る水準にあり、TTL 30秒はあくまで「イベント配信自体が
失敗・遅延した最悪ケースの保険」であって通常経路の遅延特性とは別物であることを明記する。
`promptengine.cache.ttl-seconds`として`@ConfigurationProperties`で設定可能にする
（`ApprovalPolicyProperties`と同じパターン）。

## 追加決定（a〜d）

### a. COMPILE_ONLYの結果はキャッシュ対象から除外する（最重要）

`MergeStage`は`context.mode == PipelineMode.COMPILE_ONLY`のときのみ`CompositionMode.COMPILE_ONLY`
を使う（既存実装）。COMPILE_ONLYはDraft状態の参照を許可する（ADR-0024）。Draftの更新
（`Template.newVersion`/`Fragment.newVersion`、`Prompt`のDraft編集）はいずれもPublish系
イベントを発火しない（Draftは配信対象ではないため）。よってCOMPILE_ONLYの`CompiledPrompt`を
キャッシュすると、そのDraftが後から書き換わっても無効化する契機が存在せず、CI検証
（`POST /prompts/{key}/compile`、設計書§13.1）が古いDraft内容に対して恒久的に「合格」を
返し続ける穴になる。COMPILE_ONLYはCI検証用の経路でありホットパスではないため、キャッシュの
性能上の利得もほぼない。

実装: `MergeStage`は`compositionMode == CompositionMode.STANDARD`のときのみ`PromptCache`の
`get`/`put`を呼ぶ。COMPILE_ONLYの場合は常にコンパイルを実行する（キャッシュを一切経由しない）。
`PromptCache`実装のKDocにこの制約を明記し、「COMPILE_ONLYはキャッシュされない」ことを固定する
テストを置く（Draftを書き換えてcompileを2回呼び、2回目が1回目のキャッシュを再利用しない＝
新しい内容を反映することを確認する）。

### b. TTL既定値とNFR-009の関係

決定5に記載（30秒、根拠は`OutboxRelayProperties.claimTimeoutSeconds`との整合、NFR-009は
対象外である旨の明記）。

### c. 逆依存の再帰探索への上限

コンテキスト節の事実1・2により、正しい実装は多段階の再帰探索ではなく1回の`findInbound`検索で
ある。したがって「visited setと深さ上限を持つ再帰探索」は実装しない。仮に将来Nested Prompt
（Issue #19、`DependencyKind.PROMPT`）が実装され、Prompt→Prompt依存が`dependencies`テーブルへ
書き込まれるようになった場合は、その時点で初めて多段階探索が必要になりうる（Prompt Versionが
"from"側になり得るケースがPROMPT種別で発生するため）。その際にvisited set・深さ上限を備えた
探索を追加することとし、本ADRのスコープでは「将来Issue #19実装時の要検討事項」として
`DependencyRepository`のKDocに書き残すに留める。今回、存在しないPrompt→Prompt辺を前提にした
再帰コードを書くと、テスト不可能な到達不能コードになる（CLAUDE.md「作業の進め方5」の
「到達不能なコードは削除する」に反する）ため、意図的にスコープ外とする。

### d. Redisへの実接続検証

`compose.yaml`に`redis`サービス（イメージ`redis:7`、ポート6379）は既に定義済みだが、
全11フェーズを通じて一度も接続されたことがない。既定実装（設計書§16拡張ポイント#9）として
`RedisPromptCache`（Lettuce経由）を実装し、Testcontainers（`redis:7`イメージ、
`GenericContainer`）で実際にコンテナを起動し、`get`/`put`/`invalidateByPrompt`が実際に
Redisへ読み書きできることを検証する統合テストを`tests/integration`に追加する。既存の
0件ガード（`verifyIntegrationTestExecuted`、CI実行件数が0件では静かに成功と誤認しない仕組み）
の対象に本テストが含まれることを確認する。

## 影響範囲

- `prompt-engine-domain`: `promptengine.domain.template.TemplateDomainEvent`（4イベント）・
  `promptengine.domain.fragment.FragmentDomainEvent`（4イベント）を新設。`Template`/`Fragment`の
  `create`/`newVersion`/`publish`/`archive`が`EventContext`を受け取り`Pair<Aggregate, Event>`を
  返すよう変更。`TemplateRepository`/`FragmentRepository.save`が`events`引数を取るよう変更。
  `promptengine.domain.cache.PromptCache`（§3.4準拠、新設）。`DependencyRepository`に
  TEMPLATE/FRAGMENT向け`findInbound`相当を追加。既存`promptengine.domain.cache.
  PromptCacheInvalidator`/`InMemoryPromptCacheInvalidator`は`PromptCache.invalidateByPrompt`と
  シグネチャが完全に重複するため廃止し、`CacheInvalidationSubscriber`は`PromptCache`に
  直接依存する
- `prompt-engine-core`: `CreateVersionHandler`相当の依存エッジ生成を`CompositionService.compile`
  ベースへ置き換え（`dependencyEdgesFrom`/`ExtendsFieldResolver`単体呼び出しの置換）
- `prompt-engine-infrastructure`: `JdbcTemplateRepository`/`JdbcFragmentRepository`が
  `DomainEventAppender`経由でイベントを追記。`JdbcDependencyRepository`にTEMPLATE/FRAGMENT向け
  逆引きクエリを追加。`RedisPromptCache`（新設）。`CacheInvalidationSubscriber`を
  `CacheInvalidationPayloadCodec`経由の`promptKey`/`templateKey`/`fragmentKey`解決へ修正
  （既存のaggregateId起因の無音失敗を修正）。Template/Fragment publish/archiveイベントの
  逆依存解決・SemVer範囲判定によるfan-out無効化を追加
- `prompt-engine-application`: `MergeStage`が`PromptCache`のget/put（STANDARDモードのみ）を呼ぶ
- `migration`: `V15__dependencies_findinbound_index.sql`相当（`to_kind`, `to_key`の複合indexが
  無ければ追加）。既存`dependencies`テーブルのカラム変更は無い
- 設計書§14（イベント一覧8行追加）・§1.9（NFR-002の「M1では未検証」注記を実測値に更新）・
  §16（拡張ポイント#9の既定実装欄をRedisに更新）を改訂

## 参照

- [PromptEngine_設計書.md §1.9 / §2.8 / §3.4 / §4.1 / §5.1 / §5.2 / §14 / §16](../PromptEngine_設計書.md)
- [ADR-0008: Template/Fragment ドメインモデル](0008-template-fragment-domain-model.md)
- [ADR-0009: CompositionService参照解決](0009-composition-service-reference-resolution.md)
- [ADR-0024: LoadStageのVersion状態ゲート](0024-load-stage-version-state-gate.md)
- [ADR-0025: Event Bus/Outbox Relay](0025-event-bus-outbox-relay.md)
- [ADR-0026: Evaluation/Audit Subscriber・DLQ](0026-evaluation-audit-subscribers-dlq.md)
- GitHub Issue #15（Template/Fragment Domain Event、本ADRで着手）
- GitHub Issue #77（CompiledPromptキャッシュ、本ADRで着手）
- GitHub Issue #19（Nested Prompt、決定c参照で将来のスコープとして記録）
