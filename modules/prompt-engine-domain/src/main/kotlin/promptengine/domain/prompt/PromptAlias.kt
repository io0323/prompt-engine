package promptengine.domain.prompt

import promptengine.domain.shared.SemVer

/**
 * `prompt_aliases`テーブル（設計書§12）の1行。`VersionRef.Alias`が指す実Versionを保持する。
 *
 * P8時点では[LoadStage][promptengine.application.pipeline.LoadStage]が`VersionRef.Alias`を
 * 常に未解決として扱っていた（Alias永続化の経路が無かったため）。実装ガイド§6.9は
 * Stage 1（Load）が`VersionRef`3種すべてに対応することを前提としており、本型と
 * [PromptAliasRepository]の追加によりこの欠落をP8完了の一部として解消する。
 */
data class PromptAlias(
    val promptKey: PromptKey,
    val alias: String,
    val semVer: SemVer,
) {
    init {
        require(alias.isNotBlank()) { "alias must not be blank" }
    }
}
