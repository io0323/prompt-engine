package promptengine.domain.optimization

import promptengine.domain.shared.Cost
import promptengine.domain.shared.TokenCount

/**
 * APAPのモデルメタデータを参照して構成するモデル特性（設計書§2.11・§4.4）。
 *
 * [tokenizerId]はプロバイダ名・モデル名を直接指さない不透明な識別子とする
 * （CLAUDE.md「特定のAIプロバイダ名・モデル名をコードに直接書かない」）。実際の
 * [promptengine.domain.tokenizer.TokenizerPlugin]実装への解決はDI結線側の責務。
 */
data class ModelProfile(
    val maxContextTokens: TokenCount,
    val tokenizerId: String,
    val costPerToken: Cost,
    val capabilities: Set<ModelCapability> = emptySet(),
) {
    init {
        require(tokenizerId.isNotBlank()) { "tokenizerId must not be blank" }
    }
}
