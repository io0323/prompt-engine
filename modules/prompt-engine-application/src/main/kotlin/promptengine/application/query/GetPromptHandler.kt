package promptengine.application.query

import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.PromptMetadata
import promptengine.domain.prompt.PromptMetadataRepository
import promptengine.domain.prompt.PromptRepository
import promptengine.domain.prompt.PromptVersion
import promptengine.domain.prompt.PromptVersionNotFoundException

/** `GET /prompts/{key}`（設計書§13.1、詳細+Version一覧）。トランザクション境界なし（§2.2 CQRS）。 */
data class GetPromptQuery(val key: PromptKey)

data class GetPromptResult(val metadata: PromptMetadata?, val versions: List<PromptVersion>)

class GetPromptHandler(
    private val promptRepository: PromptRepository,
    private val promptMetadataRepository: PromptMetadataRepository,
) {
    fun handle(query: GetPromptQuery): GetPromptResult {
        val prompt = promptRepository.findByKey(query.key) ?: throw PromptVersionNotFoundException.forKey(query.key)
        return GetPromptResult(promptMetadataRepository.find(query.key), prompt.versions)
    }
}
