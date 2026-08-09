package promptengine.domain.event

/**
 * Domain Eventの配信先Bus Topic（設計書§14「Busトピック」、ADR-0025決定7）。
 *
 * 設計書§14に列挙された6トピックのみを表す。Kafka等特定Brokerクライアントへの
 * 依存を持たない（`prompt-engine-domain`はフレームワーク非依存、CLAUDE.md）。
 * 実際のTopic文字列とイベント種別の対応は[EventTopicResolver]が持つ。
 */
enum class EventTopic(val topicName: String) {
    PE_PROMPT("pe.prompt"),
    PE_EXECUTION("pe.execution"),
    PE_EVALUATION("pe.evaluation"),
    PE_EXPERIMENT("pe.experiment"),
    PE_GOVERNANCE("pe.governance"),
    PE_PLUGIN("pe.plugin"),
}
