package promptengine.infrastructure.cache

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import promptengine.domain.composition.ResolvedDependency
import promptengine.domain.shared.VersionRange
import promptengine.domain.template.ast.BlockNode
import promptengine.domain.template.ast.BooleanLiteral
import promptengine.domain.template.ast.EachNode
import promptengine.domain.template.ast.ExprNode
import promptengine.domain.template.ast.ExpressionOperand
import promptengine.domain.template.ast.IfNode
import promptengine.domain.template.ast.IncludeNode
import promptengine.domain.template.ast.MacroCallNode
import promptengine.domain.template.ast.NumberLiteral
import promptengine.domain.template.ast.PromptAst
import promptengine.domain.template.ast.PropertyRef
import promptengine.domain.template.ast.StringLiteral
import promptengine.domain.template.ast.TextNode

/**
 * `CompiledPrompt`をJSONへ(逆)シリアライズするための多態型サポート（`RedisPromptCache`専用、
 * ADR-0033）。
 *
 * `prompt-engine-domain`はJacksonへ依存できない（CLAUDE.md）ため、`PromptAst`等のsealed型
 * 自体に`@JsonTypeInfo`を付けられない。Jacksonのmixin機構（型を変更せず外部からアノテーションを
 * 適用する仕組み）でこの制約を回避し、ポリモーフィズム情報だけを`prompt-engine-infrastructure`側に
 * 閉じ込める。
 *
 * `VersionRange`は多態型mixinではなく、ADR-0009が確立した唯一の往復変換（`toRangeText`/`parse`）
 * をそのまま使うシリアライザ/デシリアライザで表現する（新しい変換ロジックを作らない）。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@type")
@JsonSubTypes(
    JsonSubTypes.Type(TextNode::class, name = "TextNode"),
    JsonSubTypes.Type(ExprNode::class, name = "ExprNode"),
    JsonSubTypes.Type(IfNode::class, name = "IfNode"),
    JsonSubTypes.Type(EachNode::class, name = "EachNode"),
    JsonSubTypes.Type(BlockNode::class, name = "BlockNode"),
    JsonSubTypes.Type(IncludeNode::class, name = "IncludeNode"),
    JsonSubTypes.Type(MacroCallNode::class, name = "MacroCallNode"),
)
private interface PromptAstMixin

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@type")
@JsonSubTypes(
    JsonSubTypes.Type(PropertyRef::class, name = "PropertyRef"),
    JsonSubTypes.Type(StringLiteral::class, name = "StringLiteral"),
    JsonSubTypes.Type(NumberLiteral::class, name = "NumberLiteral"),
    JsonSubTypes.Type(BooleanLiteral::class, name = "BooleanLiteral"),
)
private interface ExpressionOperandMixin

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@type")
@JsonSubTypes(
    JsonSubTypes.Type(ResolvedDependency.TemplateDependency::class, name = "TemplateDependency"),
    JsonSubTypes.Type(ResolvedDependency.FragmentDependency::class, name = "FragmentDependency"),
)
private interface ResolvedDependencyMixin

private class VersionRangeSerializer : JsonSerializer<VersionRange>() {
    override fun serialize(
        value: VersionRange,
        gen: JsonGenerator,
        serializers: SerializerProvider,
    ) {
        val text = value.toRangeText()
        if (text == null) gen.writeNull() else gen.writeString(text)
    }
}

private class VersionRangeDeserializer : JsonDeserializer<VersionRange>() {
    override fun deserialize(
        p: JsonParser,
        ctxt: DeserializationContext,
    ): VersionRange {
        val text = if (p.currentToken() == JsonToken.VALUE_NULL) null else p.valueAsString
        return VersionRange.parse(text)
    }
}

@JsonSerialize(using = VersionRangeSerializer::class)
@JsonDeserialize(using = VersionRangeDeserializer::class)
private interface VersionRangeMixin

/** [objectMapper]に`CompiledPrompt`の(逆)シリアライズに必要なmixinを登録したコピーを返す。 */
fun compiledPromptObjectMapper(objectMapper: ObjectMapper): ObjectMapper =
    objectMapper.copy()
        .addMixIn(PromptAst::class.java, PromptAstMixin::class.java)
        .addMixIn(ExpressionOperand::class.java, ExpressionOperandMixin::class.java)
        .addMixIn(ResolvedDependency::class.java, ResolvedDependencyMixin::class.java)
        .addMixIn(VersionRange::class.java, VersionRangeMixin::class.java)
