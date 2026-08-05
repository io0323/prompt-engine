# ADR-0024: LoadStageの固定Version参照にPublished/Deprecated状態ゲートを追加する

## ステータス

Accepted

## コンテキスト

9cで初めてREST API経由の実HTTPをE2Eで検証したところ、`LoadStage.resolveVersion`が
`VersionRef.Fixed`/`VersionRef.Alias`で解決した`PromptVersion`をその状態（Draft/InReview/
Approved/Published/Deprecated）を問わず素通しで返す実装であることが判明した。このため、
`RENDER_ONLY`/`FULL_EXECUTION`モードからDraft状態のVersionを直接指定して`render`/`execute`
できてしまっていた（`VersionRef.Latest`は`resolveLatestPublished`により元々Publishedのみに
限定されていたため影響なし）。

P3c `CompositionService`は既に同種の問題を解決済みである: `CompositionMode.STANDARD`
（`COMPILE_ONLY`以外）では、参照解決候補にDraft版しか無い場合
`DraftReferenceNotAllowedException`（`promptengine.domain.composition`）
を投げてDraft参照を拒否する（ADR-0009・ADR-0012、設計書§2.10 DependencyValidation
「Draft相互参照はCompile-onlyで許可」）。この規則はTemplate/Fragmentへの**間接**参照
（Compositionが解決する依存）にのみ適用され、クライアントが`versionRef`で直接指定する
**主**PromptVersion自体には適用されていなかった。

## 決定

`LoadStage.resolveVersion`が解決した`PromptVersion`に対し、P3cが確立したのと同じ規則
（COMPILE_ONLYのみ非Published/Deprecatedを許可）を適用する。

- `PipelineMode.COMPILE_ONLY`: 全状態（Draft/InReview/Approved/Published/Deprecated）を許可
- `PipelineMode.RENDER_ONLY` / `PipelineMode.FULL_EXECUTION`: `Published`/`Deprecated`のみ許可
- 拒否時は新設の`PromptVersionStateNotAllowedException`
  （`promptengine.domain.prompt`）を投げる。設計書§13.3の既存コード`VALIDATION_FAILED`
  （400）に便乗させる（ADR-0021が`DraftReferenceNotAllowedException`を`VALIDATION_FAILED`に
  便乗させたのと同一の理由: 「リクエストされた構成が現在の状態では受理できない」という
  意味は`VALIDATION_FAILED`の既存の意味に収まり、新コードを起こすほどクライアントの対処が
  変わらない。当該Versionが`Approved`まで進めば解消するため）。新規コードは追加しない。
- `PromptVersionStateNotAllowedException`は`IllegalArgumentException`ではなく
  `RuntimeException`を継承する（`GlobalExceptionHandler`が`IllegalArgumentException`を
  直接`INVALID_REQUEST`へ写像するハンドラを持つため、継承すると`StageErrorMapper`を
  経由せず意図しないコードで応答してしまう）。
- ゲートは`VersionRef.Fixed`・`VersionRef.Alias`の両方に一律で適用する（`resolveVersion`が
  `when`で解決した結果に対して後段で共通に適用するため、分岐ごとに実装する必要がない）。
  `VersionRef.Latest`は`resolveLatestPublished`が既にPublishedのみ返すため、同じゲートを
  通しても実質的な影響はない。

## 追記: 400（VALIDATION_FAILED）と404（PROMPT_NOT_FOUND）の選択（CodeRabbitレビュー指摘）

固定Version参照が拒否された際に400を返すと、そのVersion番号自体は実在する（状態が
不適格なだけ）という情報がクライアントに漏れる（404ならVersion不在と区別が付かない）
のではないかという指摘があった。検討の結果、400を維持する:

- `compile`/`render`は`prompt:read`スコープを要求し、同じスコープで呼べる
  `GET /prompts/{namespace}/{name}`が全Version（状態問わず）を返す（設計書§13.2）ため、
  400が新たに漏らす情報は無い（`prompt:read`保持者は既にVersion一覧と状態を見られる）。
- `execute`は`prompt:execute`スコープのみで`prompt:read`を伴わない付与もあり得るため、
  この経路に限れば「指定したSemVerが実在するかどうか」を400/404の違いから読み取れる
  余地は残る。ただし`VersionRef.Fixed`はクライアントが具体的なSemVer文字列を指定する
  必要があり、総当たりで存在確認できる一覧取得オラクルにはならない（推測したSemVer
  1件ずつの存在有無に限られる）。
- 404に寄せて情報を隠すには、`LoadStage`（application層）に「呼出元がどのスコープを
  持つか」というHTTP/認可層の知識を持ち込む必要があり、Clean Architectureの層境界
  （domain/applicationはHTTP・認可を知らない、CLAUDE.md）を破る。上記の残存リスクの
  小ささに対して不釣り合いに大きい設計変更となるため見送る。
- P3c確立済みの`DraftReferenceNotAllowedException`（Composition依存の間接参照）も同じ
  理由で400のまま据え置いており、本ADRの主Version参照だけ404に倒すと、同種の状態不整合
  なのに個別参照と間接参照とでコードが割れて一貫性を欠く。

## 影響範囲

- `promptengine.domain.prompt.PromptVersionStateNotAllowedException`を新設
- `promptengine.application.pipeline.LoadStage.resolveVersion`が`PipelineMode`を追加引数に取り、
  解決結果へ状態ゲートを適用する
- `promptengine.application.pipeline.StageErrorMapper`に
  `PromptVersionStateNotAllowedException::class to VALIDATION_FAILED`を追加
- 設計書§2.13「実行時参照」に、固定Version参照でもPublished/Deprecatedに限られる旨を明記
- `PipelineStageGuardsTest`に状態（Draft/InReview/Approved/Published/Deprecated）×モード
  （COMPILE_ONLY/RENDER_ONLY/FULL_EXECUTION）の全15組み合わせのテスト、およびAlias経由でも
  同じゲートが効くことを確認するテストを追加

## 参照

- 設計書§2.10（Validation仕様・DependencyValidation）・§2.13（Version管理仕様）・§13.3
- ADR-0009: CompositionService参照解決基盤
- ADR-0012: ValidationEngine（DependencyValidation Rule）
- ADR-0021: Composition関連例外のHTTPコードを設計書§13.3に追加する
  （`DraftReferenceNotAllowedException` → `VALIDATION_FAILED`と同一の判断を踏襲）
- `promptengine.application.pipeline.LoadStage`のKDoc
