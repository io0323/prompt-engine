# ADR-0010: 合成規則（extendsマージ・import・include束縛・macro展開）とCompiledPrompt本体合成

## ステータス

Accepted

## コンテキスト

P3c2（`docs/PromptEngine_ClaudeCode実装ガイド.md` §6.4関連スコープ、ADR-0009の
PR2）でCompositionServiceの合成規則本体（extendsマージ・import解決・include展開・
macro展開・`CompiledPrompt.body`合成）を実装するにあたり、着手前にユーザーへ3点
（include変数束縛スコープ・super()の意味論・macroスコープ）を確認した
（[[project_prompt_engine_p3b_template_fragment|P3b]]・ADR-0009で確立した
「非自明なドメイン解釈はADR化してから実装する」運用を踏襲）。確認の過程で、
ユーザーが未確定のまま残した3つの派生論点（Include必須変数の未解決タイミング・
super()の異常系・PRサイズ）と、実装上必然的に生じる補助的な解釈（変数置換の
具体的な機構・block以外のトップレベルノードの扱い・変数マージの優先順位）が
見つかったため、あわせて本ADRで確定する。

## 決定

### 1. Include変数束縛スコープ

- **透過継承の対象はFragment側で宣言された変数名に限る。** 呼出側スコープの
  無関係な変数がFragmentへ偶然漏れ込むことを防ぐ（再利用性・予測可能性の担保）。
- **「呼出側スコープ」の定義**: 現在の合成チェーン上でこれまでに宣言された
  変数名の累積集合。具体的には「ルートPromptVersion自身の`variables`」
  ∪「extendsチェーン上の各TemplateVersionの`variables`」∪「この呼出に至る
  までに（外側のIncludeとして）展開されたFragmentが宣言した`variables`」。
  ネストしたInclude（Fragment内でさらにIncludeする場合）では、外側のFragmentの
  宣言変数も「呼出側スコープ」に含まれる（再帰的に一貫）。
- **優先順位**: 明示束縛（`k=v`）が常に勝つ。これは実際には「衝突」ではない
  ──ある変数名は「明示束縛された」か「未指定（透過継承候補）」のいずれか一方
  にしかならないため、構造的に優先順位を判定するコードは不要（束縛マップに
  キーが存在するかどうかで完全に分岐する）。
- **必須変数が未解決の場合（Compile時エラー、決定4参照）**: Fragmentが
  `required: true`で宣言した変数が、(a)明示束縛にも無く、かつ(b)呼出側スコープ
  （上記定義の変数名集合）にも無い場合、`IncludeRequiredVariableUnresolvedException`
  を投げる。これは「その名前を供給する変数宣言がどこにも存在せず、実行時
  （Stage4 Resolve Variables）に至っても構造的に解決不可能である」ことを
  Compile時点で検出するものであり、Stage4の`VARIABLE_UNRESOLVED`
  （実行時パラメータの値が無い場合のエラー）とは別種のチェックである。

### 2. 変数置換の機構（Include束縛・macro引数で共通）

Fragment/macro本体のASTを再帰的に走査し、`PropertyRef(path)`の先頭セグメント
（`path.first()`）が束縛/引数マップのキーと一致する式を、束縛Expressionで
構造的に置換する。

- 束縛Expressionのoperandが`PropertyRef(boundPath)`なら、
  `PropertyRef(boundPath + path.drop(1))`に置換する（`k.field`のようなドット
  参照の先頭だけを差し替え、残りのパスは保持する）。
- 束縛Expressionのoperandが`Literal`（文字列/数値/真偽値）で、かつ`path`が
  2セグメント以上（`k.field`のように束縛対象をさらに掘り下げようとしている）
  場合は、リテラルへのプロパティアクセスが意味を持たないため
  `InvalidVariableSubstitutionException`を投げる。
- フィルタは「束縛Expression自身のフィルタ」→「置換対象Expression自身の
  フィルタ」の順に連結する（束縛時点での変換を先に適用し、Fragment/macro側の
  利用時変換を後から重ねる）。
- この置換ロジックはIncludeの`k=v`束縛とmacro呼出の引数束縛の両方で共有する
  唯一の実装とする（`prompt-engine-core`、`promptengine.engine.compiler`）。

### 3. super()の意味論

- `{{ super() }}`は構文上、`MacroCallNode(name = "super", arguments = emptyMap())`
  として解析される（P3aのパーサは`name(...)`形状を一律`MacroCallNode`として
  解析し、`super`という名前に意味的な特別扱いを一切行わない。「super」の解釈は
  3c＝CompositionServiceの責務）。**引数付き`super(x=1)`や`super`という名前の
  ユーザー定義macroは、super挿入としては扱わず、通常のmacro呼出として解決を
  試みる**（スコープ内に`super`という名前のmacroが無ければ通常どおり
  `MacroNotFoundException`になる）。
- **多段継承でsuper()が指す先は直近の親**。「直近の親」とは、extendsチェーンを
  根本（`extends`が`null`のTemplate）から末端（実際にコンパイルする
  Prompt/Template）へ向かって順にブロックをマージしていったときの、
  1つ手前の階層で**既に確定した**（そのTemplate自身のsuper()が解決済みの）
  role別ブロック内容を指す。したがって「親が祖父のblockをそのまま継承している
  （親自身はそのroleのblockを再宣言していない）」場合、子のsuper()は祖父由来の
  内容を指すことになる（透過継承の自然な帰結）。
- **同一block内でsuper()を複数回呼ぶことは禁止**。block本体のAST木
  （`{{#if}}`/`{{#each}}`内を含め再帰的に）を走査して`super`呼出を数え、
  2件以上見つかった場合は`DuplicateSuperCallException`を投げる。
- **親（直近の親としてこの階層まで確定している内容）に存在しないroleの
  blockでsuper()を呼んだ場合はエラー**（`SuperWithoutParentBlockException`）。
  黙って空文字列に展開すると、テンプレ側のtypoや親block削除の見落としに
  気づけないため。根本のTemplate（`extends`が`null`）自身がsuper()を呼んでいる
  場合も同様にこの例外を投げる（根本には親が存在しないため）。
- super()の解決は「extendsマージ」段階でのみ行われる。`{{#block}}`の外
  （トップレベル・Fragment内・macro本体内）に現れた`super()`はextendsマージの
  走査対象にならず、そのままmacro展開段階に渡され、通常の未定義macro扱いになる。

### 4. Include必須変数の未解決はCompile時エラーとする

決定1で述べた`IncludeRequiredVariableUnresolvedException`により、Compose段階
（設計書§2.6ステージ3）で静的に検出できるケースは即座に失敗させる
（fail-fast）。「一見成功したCompiledPromptが実行時に必ず失敗する」状態を
許さない。Stage4（Resolve Variables）の`VARIABLE_UNRESOLVED`は、実行時パラメータ
（Runtime変数の値）が渡されなかった場合のための、引き続き独立したチェックで
あり、本決定によって不要になるものではない。

### 5. macroのスコープ

macro呼出はPrompt/Template/Fragmentそれぞれの**宣言単位に閉じる**
（`macros:`フロントマターを持つ主体自身が定義したmacroのみを、その主体の本文
から呼び出せる）。Includeで取り込んだFragment内のmacro呼出を、呼出元
（Prompt/Template）で定義されたmacroで解決することはしない。再利用部品
（Fragment）が呼出元の定義に依存すると、同じFragmentが文脈によって別の意味に
なり、決定性の推論が破綻するため。

この帰結として、**宣言単位のどのmacro定義にも一致しないmacro呼出は
`MacroNotFoundException`を投げる**（スコープが閉じている以上、これは
必然的に生じる異常系であり、独立した確認は行わなかった）。

macroの再帰検出（`MacroRecursionException`、PR1で定義済み）は、macro呼出が
宣言単位に閉じることの帰結として、**同一宣言単位が持つmacro定義集合の中だけ**
で起こりうる（他の主体のmacroを再帰的に辿ることは構造的に無い）。呼出名の
スタックによるDFSで検出する。

### 6. extendsマージのアルゴリズムとブロック以外のトップレベルノードの扱い

`ReferenceResolver.resolveExtendsChain`が返す`List<ResolvedDependency.TemplateDependency>`
は「直近の親→…→根本」の順（PR1実装済み、変更しない）。マージは逆順
（根本→直近の親→リーフ＝実際にコンパイルするPrompt/Template自身）に行う。

各階層で、その階層自身の本文（`content.source`を`PromptDslParser`でパースして
得た`List<PromptAst>`）から`{{#block role}}`ノードを`role`をキーに取り出す。

- 直前までに確定した`role`別内容が無い（この階層で初めて登場する`role`、または
  根本階層）: その階層自身のblock本体をそのまま採用する（ただし本文中に
  super()があれば決定3により`SuperWithoutParentBlockException`）。
- 直前までに確定した`role`別内容がある: その階層のblock本体に対して決定3の
  super()置換を行い、結果を「この階層時点でのroleの確定内容」として上書きする。
- この階層がそのroleのblockを宣言していない: 直前までの確定内容をそのまま
  引き継ぐ（変更しない）。

**`{{#block}}`ではないトップレベルノード**（`{{#block}}`の外側にある
`TextNode`/`ExprNode`等）は、**リーフ階層（実際にコンパイルするPrompt自身）の
ものだけを最終出力に含める**。extendsチェーン上の祖先Template（根本〜直近の親）
が持つブロック外のトップレベルノードはマージ対象にしない（§15.3が言及するのは
block単位のマージのみであり、「同名」で対応付けられる概念がblock以外に存在
しない。P3a/P3bの既存サンプルはすべて全内容をblock内に収めており、実務上の
影響は無いと判断した）。extendsされることを意図するTemplateは、内容をすべて
`{{#block}}`内に収める運用とする。

**最終出力の順序**: リーフ自身のトップレベルノード列を基準順序とし、その中に
現れる`{{#block role}}`は決定した「roleの確定内容」に差し替える。リーフが
宣言していないが祖先のいずれかから継承された`role`（純粋な継承のみで
リーフ自身は触れていないblock）は、その`role`が合成チェーン中で最初に
登場した階層の相対順序で、リーフのトップレベルノード列の末尾に追加する。
これにより、同一リポジトリ状態からは常に同じノード順序が得られる（決定性）。

### 7. Import解決とnamespace

`imports:`フロントマターは新設の`promptengine.engine.compiler.ImportsFieldMapper`
（`ExtendsFieldMapper`と同型のパターン、DSLの生フィールドから構造化値への
唯一の変換経路）で`List<ImportDeclaration>`（`alias: String, fragmentKey: FragmentKey,
range: VersionRange`）に変換する。`ImportDeclaration`はDB永続化対象ではなく
（ADR-0009決定1がextendsのみをAggregate構造化フィールドへ昇格した判断を踏襲し、
importsは昇格しない）、`prompt-engine-core`内部の一時的な値型として扱う。

- `imports:`内で同一aliasが2回以上宣言された場合は`DuplicateImportAliasException`
  を投げる（どちらの宣言を指すか一意に定まらないため）。
- Include先の解決（`{{> target[@range] }}`）:
  1. `target`が`"prompt:"`で始まる場合 → `NestedPromptNotSupportedException`
     （ADR-0009決定3で確定済み、本ADRで初めて実装に反映する）。
  2. `target`が宣言済みaliasと一致する場合 → そのaliasの`fragmentKey`を使う。
     Version範囲は、Includeタグ自身に`@range`指定があればそれを優先し
     （明示指定が常に勝つという、決定1と同じ「その場の明示指定が優先」の
     考え方の一貫適用）、無ければ`imports:`宣言側の`range`を使う。
  3. どちらでもない場合 → `target`をFragmentKeyの生の値として直接解決する
     （§15.5 `<alias|fragmentKey>`）。Version範囲はIncludeタグの`@range`指定
     （無ければ`VersionRange.Latest`）。
- **多重取込の正規化（§15.4）**: 「同一Fragmentの多重取込を1回に正規化する」は、
  ADR-0009決定4のDFSメモ化を、Fragment解決（`(FRAGMENT, key, resolvedVersion)`
  ノード）にもそのまま適用することを意味する。同じ`(key, resolvedVersion)`に
  複数のalias・複数のInclude箇所から到達しても、Fragment自身の内部合成
  （そのFragmentが持つ独自のimport/include/macro解決や、SemVer範囲解決・
  Status検証）は1回だけ行い結果を再利用する。ただし**Include呼出側での展開
  （変数束縛の適用・出力ASTへの挿入）は呼出箇所ごとに独立して行う**
  （束縛が異なれば挿入される内容も異なるため、「1回だけ展開」は内部合成の
  重複排除を指すのであって、呼出箇所ごとの最終テキストの重複排除ではない）。

### 8. CompiledPromptの`variables`・`contextRequirement`マージ

- **variables**: 名前で重複排除する。同名の変数が複数箇所（リーフPrompt自身・
  extendsチェーンのTemplate群・includeされたFragment群）で宣言されていた場合、
  「リーフPrompt自身の宣言」→「extendsチェーン（直近の親から根本の順）の
  宣言」→「Fragment群の宣言（本文中に最初に出現した箇所を優先、深さ優先
  順）」の順に走査し、最初に見つかった宣言を採用する（同一名で異なる制約を
  持つ定義を書くこと自体は運用上のミスだが、CompositionServiceはそれを検出
  するバリデーションルールではない。Validation Engine（P5、未実装）の責務。
  ここでは決定性だけを保証する）。macroのparamsは呼出のたびに完全に置換
  （決定2）されるため、`CompiledPrompt.variables`には一切現れない。
- **contextRequirement**: 現行ドメインモデルでは`TemplateVersion`/
  `FragmentVersion`に`contextRequirement`フィールドが存在せず
  （`PromptVersion`のみが保持する）、マージすべき複数のContext要求は実質的に
  存在しない。`CompiledPrompt.contextRequirement`は常に、コンパイル対象の
  `PromptVersion.contextRequirement`をそのまま用いる。`CompiledPrompt`の
  KDoc（ADR-0009）が「マージ結果」と書いているのは将来Template/Fragmentが
  Context要求を持つ場合に備えた表現であり、本フェーズでは実質的に
  「Prompt自身の値」に一致することを、本ADRで明確化する。

### 9. `CompositionService`のインターフェース配置

`promptengine.domain.composition.CompositionService`をdomainのInterfaceとして
新設し、`compile(promptVersion: PromptVersion, mode: CompositionMode): CompiledPrompt`
を持つ。実装`CompositionServiceImpl`は`prompt-engine-core`
（`promptengine.engine.compiler`）に置く。`ImportDeclaration`/`MacroDeclaration`
等のDSL中間表現はdomainに置かず、`prompt-engine-core`内部の型とする
（domainはパーサに依存できないため、これらの型を扱うロジック自体が
core側に閉じる必要がある。ADR-0008のFragment循環検出非対応の理由付けと同型）。

### 10. PR分割

実装量が大きいため、ユーザー確認の上でADR-0009のPR2をさらに3分割する。

- **PR2a**（`feat/p3c2a-extends-import`、本ADRを含む）: 決定2・3・6（extends
  ブロックマージ・super()）・決定7（import解決・namespace・多重取込正規化）。
  `ReferenceResolver`の内部実装（Template向けのSemVer範囲・Status検証）を
  Fragment解決でも再利用できるよう共通化する。
- **PR2b**（`feat/p3c2b-include-binding`、PR2aマージ後）: 決定1・2・4
  （include展開・変数束縛・必須変数未解決検出）。PR2aのFragment解決基盤を利用する。
- **PR2c**（`feat/p3c2c-macro-compiled-prompt`、PR2bマージ後）: 決定5（macro
  ローカル展開・再帰検出）・決定8・9（`CompositionService.compile()`本体、
  解決順序 extends→import→include→macro の結線、`CompiledPrompt`合成）・
  決定性/サイズ上限/カバレッジを検証する結合テスト・`docs/dsl/samples`への
  合成規則用フィクスチャ追加。

各PRは`git switch main && git pull`後に前PRの成果を含んだ状態から分岐する
（順次マージ運用、ADR-0009のPR1/PR2分割と同じ方針）。

## 影響範囲

- 設計書§15.3: super()の異常系（親に無いblockでの呼出・同一block内複数回呼出は
  エラー）・多段継承の解決方向（根本→直近の親→リーフ）を明記。
- 設計書§15.4: 「同一Fragmentの多重取込を1回に正規化する」が指すのは
  Fragment自身の内部合成の重複排除であり、呼出箇所ごとの束縛適用・出力挿入は
  重複排除の対象外であることを明記。
- 設計書§15.5: include変数束縛の透過継承範囲（Fragment宣言変数名限定）・
  必須変数が呼出側スコープにも見つからない場合はCompile時エラーであることを明記。
- 設計書§15.6: macroスコープがPrompt/Template/Fragmentの宣言単位に閉じること、
  未定義macro呼出はエラーであることを明記。
- `prompt-engine-domain`:
  - `promptengine.domain.composition.CompositionService`（Interface）を新設
  - `promptengine.domain.composition.CompositionException`に
    `IncludeRequiredVariableUnresolvedException`/`SuperWithoutParentBlockException`/
    `DuplicateSuperCallException`/`MacroNotFoundException`/
    `InvalidVariableSubstitutionException`/`DuplicateImportAliasException`を追加
- `prompt-engine-core`:
  - `promptengine.engine.compiler.ImportsFieldMapper`（決定7）、
    `promptengine.engine.compiler.MacrosFieldMapper`（決定5、`macros:`の
    `body`を`BodyParser`でパースして`MacroDeclaration`にする）を新設
  - `promptengine.engine.compiler.ExpressionSubstitution`（決定2、Include束縛・
    macro引数で共有）を新設
  - `promptengine.engine.compiler.ReferenceResolver`を、SemVer範囲・Status検証の
    選択ロジックがTemplate/Fragment双方から再利用できるよう内部リファクタする
    （PR1で確立済みの仕組みをそのまま使う、という指示に対応。公開APIは変更しない）
  - `promptengine.engine.compiler.CompositionServiceImpl`（決定9）を新設

## 参照

- [PromptEngine_設計書.md §2.6 / §15.3 / §15.4 / §15.5 / §15.6](../PromptEngine_設計書.md)
- [PromptEngine_ClaudeCode実装ガイド.md §6.4](../PromptEngine_ClaudeCode実装ガイド.md)
- [ADR-0009: CompositionServiceの参照解決基盤（キーグラフDFS・SemVer範囲・Status検証・CompiledPrompt）](0009-composition-service-reference-resolution.md)（本ADRが決定9で参照する型・決定7で拡張する多重取込正規化の基盤）
