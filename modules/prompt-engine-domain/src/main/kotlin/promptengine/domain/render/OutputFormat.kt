package promptengine.domain.render

/**
 * 出力フォーマット（設計書§3.4 `OutputFormatter.format(): OutputFormat`）。
 *
 * 本来`OutputFormatter`自体はP7（実装ガイド§6.8）で追加するInterfaceだが、
 * [RenderedPrompt.outputFormat]（P6）が先にこの型を必要とするため、P6の時点で
 * `domain.render`に新設する（ADR-0013決定8）。P7で`OutputFormatter`がこの型を再利用する。
 */
enum class OutputFormat {
    JSON,
    XML,
    MARKDOWN,
    TEXT,
}
