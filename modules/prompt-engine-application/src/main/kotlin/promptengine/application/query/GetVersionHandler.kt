package promptengine.application.query

import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.PromptRepository
import promptengine.domain.prompt.PromptVersion
import promptengine.domain.prompt.PromptVersionNotFoundException
import promptengine.domain.shared.SemVer

/** `GET /prompts/{key}/versions/{v}`（設計書§13.1、Version内容取得）。 */
data class GetVersionQuery(val key: PromptKey, val semVer: SemVer)

class GetVersionHandler(
    private val promptRepository: PromptRepository,
) {
    fun handle(query: GetVersionQuery): PromptVersion {
        val prompt = promptRepository.findByKey(query.key) ?: throw PromptVersionNotFoundException.forKey(query.key)
        return prompt.versions.find { it.semVer == query.semVer } ?: throw PromptVersionNotFoundException(query.semVer)
    }
}
