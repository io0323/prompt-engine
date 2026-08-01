package promptengine.domain.prompt

import promptengine.domain.shared.SemVer

/**
 * Prompt Aggregate内に指定したSemVerのVersionが存在しないときに投げるドメイン例外。
 *
 * [forKey]は、Prompt自体（`PromptKey`）が見つからない場合に使う（設計書§2.6ステージ1
 * 「Version存在?」の判定は、キー自体の不在とVersion不在を区別せず同じ`PROMPT_NOT_FOUND`
 * として扱うため、ADR-0015 Pipeline Stage 1 Loadはどちらの場合もこの例外を投げる）。
 */
class PromptVersionNotFoundException private constructor(message: String) : NoSuchElementException(message) {
    constructor(semVer: SemVer) : this("PromptVersion not found: $semVer")

    companion object {
        /** [key]自体（Prompt Aggregate）が見つからない場合。 */
        fun forKey(key: PromptKey): PromptVersionNotFoundException =
            PromptVersionNotFoundException("Prompt not found: ${key.value}")
    }
}
