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
     * [promptKey]の[semVer]が参照する依存の一覧を[edges]で置き換える（Version作成コマンドが呼ぶ）。
     * 既存の行があれば削除してから[edges]を挿入する（再実行しても同じ結果になる）。
     */
    fun replaceOutbound(
        promptKey: PromptKey,
        semVer: SemVer,
        edges: List<DependencyEdge>,
    )
}
