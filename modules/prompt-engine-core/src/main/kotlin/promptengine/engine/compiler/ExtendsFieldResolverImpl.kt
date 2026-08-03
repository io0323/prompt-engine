package promptengine.engine.compiler

import promptengine.domain.template.ExtendsFieldResolver
import promptengine.domain.template.ExtendsRef
import promptengine.engine.parser.PromptDslParser

/**
 * [ExtendsFieldResolver]の実装（P9b、Issue #21）。DSL全文を[PromptDslParser]でパースし、
 * front matterの`extends`生値を[ExtendsFieldMapper]に通す、という「保存されたExtendsRefは
 * 必ずcontent.sourceのパース結果である」という整合性を守る唯一の経路をそのまま提供する。
 */
class ExtendsFieldResolverImpl(
    private val parser: PromptDslParser = PromptDslParser(),
) : ExtendsFieldResolver {
    override fun resolve(source: String): ExtendsRef? {
        val document = parser.parse(source)
        return ExtendsFieldMapper.parse(document.frontMatter.fields["extends"])
    }
}
