package promptengine.domain.fragment

/**
 * Fragment Aggregateの永続化インターフェース。実装は `prompt-engine-infrastructure`（P3b）で行う。
 *
 * [promptengine.domain.prompt.PromptRepository] と異なり `events` 引数を取らない。
 * Fragment Aggregateは本フェーズでDomain Eventを発行しないため（ADR-0008）。
 */
interface FragmentRepository {
    /** [key] に一致するFragmentを返す。一致するFragmentが存在しない場合は `null` を返す。 */
    fun findByKey(key: FragmentKey): Fragment?

    /** [fragment] の現在状態（全Version）を保存する。保存後の状態を反映した `Fragment` を返す。 */
    fun save(fragment: Fragment): Fragment
}
