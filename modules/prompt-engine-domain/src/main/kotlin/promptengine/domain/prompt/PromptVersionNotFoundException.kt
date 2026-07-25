package promptengine.domain.prompt

import promptengine.domain.shared.SemVer

/**
 * Prompt Aggregate内に指定したSemVerのVersionが存在しないときに投げるドメイン例外。
 */
class PromptVersionNotFoundException(semVer: SemVer) : NoSuchElementException("PromptVersion not found: $semVer")
