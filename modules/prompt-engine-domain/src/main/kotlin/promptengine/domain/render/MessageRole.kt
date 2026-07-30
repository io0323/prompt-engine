package promptengine.domain.render

/**
 * [RenderedPrompt.messages]が持つ抽象role（設計書§2.9「roleはsystem/user/assistant/toolの
 * 抽象role」）。
 *
 * DSL著者がブロックとして書ける
 * [BlockRole][promptengine.domain.template.ast.BlockRole]（system/user/assistantの3値、
 * 設計書§15.1）とは別レイヤーの型（ADR-0013決定2）。[TOOL]に対応する`BlockRole`値は
 * 存在しないため、M1の`RenderEngine`から[TOOL]が生成されることは構造的に無い。
 * 将来のマルチターン実行（Tool結果のreplay等）向けに予約された値。
 */
enum class MessageRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL,
}
