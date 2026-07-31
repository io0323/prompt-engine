package promptengine.domain.execution

import promptengine.domain.shared.TokenCount

/**
 * 実行1回分のトークン使用量（設計書§13.2 `usage {inputTokens, outputTokens}`、ADR-0014決定2）。
 *
 * `cost`は含めない。`ModelProfile.costPerToken`（ADR-0013）から呼出側が導出できる値であり、
 * 一次データではないため。
 */
data class Usage(val inputTokens: TokenCount, val outputTokens: TokenCount)
