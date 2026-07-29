package promptengine.engine.resolver

import promptengine.domain.context.ContextRequirement

/**
 * 1つのContextスコープの解決を担うResolver（設計書§3.4・§16-3、拡張ポイント#3）。
 *
 * [ContextRequirement.scope]はDomain側では自由記述の`String`のため、こちらも
 * `String`で揃える。[resolve]はそのスコープで利用可能な `path→値` を返す
 * （宣言されたrequired/optionalとの突き合わせ・マージは[ContextResolverImpl]の責務）。
 */
interface ContextResolver {
    fun scope(): String

    fun resolve(
        requirement: ContextRequirement,
        request: PromptRequest,
    ): Map<String, Any>
}
