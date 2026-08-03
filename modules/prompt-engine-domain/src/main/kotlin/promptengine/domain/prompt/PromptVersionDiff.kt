package promptengine.domain.prompt

import promptengine.domain.shared.SemVer

/**
 * `GET /prompts/{key}/diff?from=&to=`（設計書§13.1）の結果。
 *
 * 行単位のテキストDiffではなく、構造化フィールド単位で変更有無を示す（Version Manager
 * の責務「Diff」の最小実装、P9b）。テキスト差分そのものが必要な場合は`fromContentHash`/
 * `toContentHash`の不一致で変更検知した上で、呼出元が`content`本文を別途取得して比較する。
 */
data class PromptVersionDiff(
    val key: PromptKey,
    val from: SemVer,
    val to: SemVer,
    val contentChanged: Boolean,
    val fromContentHash: String,
    val toContentHash: String,
    val variablesChanged: Boolean,
    val contextRequirementsChanged: Boolean,
    val extendsChanged: Boolean,
    val validationChanged: Boolean,
    val outputChanged: Boolean,
) {
    companion object {
        fun of(
            key: PromptKey,
            from: PromptVersion,
            to: PromptVersion,
        ): PromptVersionDiff =
            PromptVersionDiff(
                key = key,
                from = from.semVer,
                to = to.semVer,
                contentChanged = from.content.contentHash != to.content.contentHash,
                fromContentHash = from.content.contentHash,
                toContentHash = to.content.contentHash,
                variablesChanged = from.variables != to.variables,
                contextRequirementsChanged = from.contextRequirements != to.contextRequirements,
                extendsChanged = from.extends != to.extends,
                validationChanged = from.validation != to.validation,
                outputChanged = from.output != to.output,
            )
    }
}
