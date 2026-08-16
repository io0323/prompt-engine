package promptengine.domain.dependency

import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.SemVer

/**
 * `GET /prompts/{key}/dependencies?direction=in|out`（設計書§13.1）、および`publish`ガード
 * （設計書§2.5「依存先が全てPublished」）を支えるRepository（ADR-0017、P9bで書き込みを追加）。
 */
interface DependencyRepository {
    /** [promptKey]のPublished Version（無ければ最新Version）が直接参照するTemplate/Fragment/Promptの一覧（direction=out）。 */
    fun findOutbound(promptKey: PromptKey): List<DependencyEdge>

    /**
     * [promptKey]の[semVer]（Published/最新に関わらず、明示的に指定したVersion）が直接参照する
     * Template/Fragment/Promptの一覧。`publish`ガード評価（対象Versionの依存を見る必要があり、
     * 「Publishedか最新」の代表Versionでは不正確なため）に使う。
     */
    fun findOutbound(
        promptKey: PromptKey,
        semVer: SemVer,
    ): List<DependencyEdge>

    /** [promptKey]（任意Version）をToとして参照している他Promptの依存関係の一覧（direction=in）。 */
    fun findInbound(promptKey: PromptKey): List<DependencyEdge>

    /**
     * [kind]（TEMPLATE/FRAGMENT）の[key]をToとして参照している全Promptの依存関係の一覧
     * （ADR-0033決定3）。`dependencies.from_version_id`はPrompt Versionにのみ外部キー制約が
     * あり（V1__init.sql）、CompositionServiceが生成する`CompiledPrompt.dependencies`は
     * extends祖先チェーン・Fragment include連鎖（Fragment内Fragmentを含む）を
     * コンパイル時点で1階層（Prompt起点）に平坦化済みのフルセットであるため
     * （`ReferenceResolver`/`FragmentResolver`、`prompt-engine-core`）、多段階のグラフ探索は
     * 不要で本メソッド1回の検索で「このTemplate/Fragmentに直接・間接に依存している
     * 全Prompt」が求まる。
     */
    fun findInboundTemplateOrFragment(
        kind: DependencyKind,
        key: String,
    ): List<DependencyEdge>

    /**
     * [promptKey]の[semVer]が参照する依存の一覧を[edges]で置き換える（Version作成コマンドが呼ぶ）。
     * 既存の行があれば削除してから[edges]を挿入する（再実行しても同じ結果になる）。
     */
    fun replaceOutbound(
        promptKey: PromptKey,
        semVer: SemVer,
        edges: List<DependencyEdge>,
    )
}
