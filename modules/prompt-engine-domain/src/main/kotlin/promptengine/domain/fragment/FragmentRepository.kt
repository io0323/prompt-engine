package promptengine.domain.fragment

/**
 * Fragment Aggregateの永続化インターフェース。実装は `prompt-engine-infrastructure`（P3b）で行う。
 *
 * [promptengine.domain.prompt.PromptRepository] と同じく `events` を受け取り、
 * `domain_events`/`outbox`へ追記する（M2-3、ADR-0033。ADR-0008時点は`events`を
 * 取らなかったが、Issue #15の解消によりPromptと同じ形に揃えた）。
 */
interface FragmentRepository {
    /** [key] に一致するFragmentを返す。一致するFragmentが存在しない場合は `null` を返す。 */
    fun findByKey(key: FragmentKey): Fragment?

    /** [fragment] の現在状態（全Version）を保存し、[events] を追記する。保存後の状態を反映した `Fragment` を返す。 */
    fun save(
        fragment: Fragment,
        events: List<FragmentDomainEvent>,
    ): Fragment
}
