package promptengine.application.query

import promptengine.domain.prompt.Prompt
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.PromptRepository
import promptengine.domain.prompt.PromptVersion
import promptengine.domain.prompt.PromptVersionDiff
import promptengine.domain.prompt.PromptVersionNotFoundException
import promptengine.domain.shared.SemVer

/** `GET /prompts/{key}/diff?from=&to=`（設計書§13.1、Version Diff）。 */
data class DiffQuery(val key: PromptKey, val from: SemVer, val to: SemVer)

class DiffHandler(
    private val promptRepository: PromptRepository,
) {
    fun handle(query: DiffQuery): PromptVersionDiff {
        val prompt = promptRepository.findByKey(query.key) ?: throw PromptVersionNotFoundException.forKey(query.key)
        val from = versionOf(prompt, query.from)
        val to = versionOf(prompt, query.to)
        return PromptVersionDiff.of(query.key, from, to)
    }

    private fun versionOf(
        prompt: Prompt,
        semVer: SemVer,
    ): PromptVersion = prompt.versions.find { it.semVer == semVer } ?: throw PromptVersionNotFoundException(semVer)
}
