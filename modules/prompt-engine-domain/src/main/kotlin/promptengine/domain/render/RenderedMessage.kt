package promptengine.domain.render

/**
 * [RenderedPrompt.messages]の1件（設計書§2.9 `{role, content}`）。
 */
data class RenderedMessage(val role: MessageRole, val content: String)
