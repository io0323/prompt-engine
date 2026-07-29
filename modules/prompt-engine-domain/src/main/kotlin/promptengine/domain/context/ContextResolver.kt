package promptengine.domain.context

import promptengine.domain.shared.PromptRequest

/**
 * 1つのContextスコープの解決を担うResolver（設計書§3.4・§16-3、拡張ポイント#3）。
 *
 * Interfaceはdomainに置き、実装（7スコープ標準）は`prompt-engine-core`が持つ
 * （ADR-0011決定4）。[scope]はこのDomain型では自由記述の`String`のため、こちらも
 * `String`で揃える。[resolve]はそのスコープで利用可能な `path→値` を返す
 * （宣言されたrequired/optionalとの突き合わせ・マージは`ContextResolverImpl`の責務）。
 */
interface ContextResolver {
    fun scope(): String

    fun resolve(
        requirement: ContextRequirement,
        request: PromptRequest,
    ): Map<String, Any>
}
