package promptengine.domain.prompt

/**
 * PromptDeprecatedイベントの発生理由（ADR-0005）。
 */
enum class DeprecationReason {
    /** deprecate操作による明示的な廃止。 */
    MANUAL,

    /** publish/rollbackによる自動supersede。 */
    SUPERSEDED,
}
