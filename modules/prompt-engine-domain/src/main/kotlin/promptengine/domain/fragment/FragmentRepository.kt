package promptengine.domain.fragment

/**
 * Fragment Aggregateの永続化インターフェース。実装は `prompt-engine-infrastructure`（P3b）で行う。
 *
 * [promptengine.domain.prompt.PromptRepository] と異なり `events` 引数を取らない。
 * Fragment Aggregateは本フェーズでDomain Eventを発行しないため（ADR-0008）。
 */
interface FragmentRepository {
    fun findByKey(key: FragmentKey): Fragment?

    fun save(fragment: Fragment): Fragment
}
