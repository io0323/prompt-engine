package promptengine.domain.template

/**
 * Template Aggregateの永続化インターフェース。実装は `prompt-engine-infrastructure`（P3b）で行う。
 *
 * [promptengine.domain.prompt.PromptRepository] と異なり `events` 引数を取らない。
 * Template Aggregateは本フェーズでDomain Eventを発行しないため（ADR-0008）。
 */
interface TemplateRepository {
    /** [key] に一致するTemplateを返す。一致するTemplateが存在しない場合は `null` を返す。 */
    fun findByKey(key: TemplateKey): Template?

    /** [template] の現在状態（全Version）を保存する。保存後の状態を反映した `Template` を返す。 */
    fun save(template: Template): Template
}
