package promptengine.domain.template

/**
 * Template Aggregateの永続化インターフェース。実装は `prompt-engine-infrastructure`（P3b）で行う。
 *
 * [promptengine.domain.prompt.PromptRepository] と異なり `events` 引数を取らない。
 * Template Aggregateは本フェーズでDomain Eventを発行しないため（ADR-0008）。
 */
interface TemplateRepository {
    fun findByKey(key: TemplateKey): Template?

    fun save(template: Template): Template
}
