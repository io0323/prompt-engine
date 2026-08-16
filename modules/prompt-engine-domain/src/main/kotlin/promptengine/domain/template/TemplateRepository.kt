package promptengine.domain.template

/**
 * Template Aggregateの永続化インターフェース。実装は `prompt-engine-infrastructure`（P3b）で行う。
 *
 * [promptengine.domain.prompt.PromptRepository] と同じく `events` を受け取り、
 * `domain_events`/`outbox`へ追記する（M2-3、ADR-0033。ADR-0008時点は`events`を
 * 取らなかったが、Issue #15の解消によりPromptと同じ形に揃えた）。
 */
interface TemplateRepository {
    /** [key] に一致するTemplateを返す。一致するTemplateが存在しない場合は `null` を返す。 */
    fun findByKey(key: TemplateKey): Template?

    /** [template] の現在状態（全Version）を保存し、[events] を追記する。保存後の状態を反映した `Template` を返す。 */
    fun save(
        template: Template,
        events: List<TemplateDomainEvent>,
    ): Template
}
