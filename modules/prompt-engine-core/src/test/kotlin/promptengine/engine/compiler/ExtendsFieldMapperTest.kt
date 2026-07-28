package promptengine.engine.compiler

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.shared.SemVer
import promptengine.domain.shared.VersionRange
import promptengine.domain.template.ExtendsRef
import promptengine.domain.template.TemplateKey
import promptengine.engine.parser.PromptDslParser

/**
 * フロントマターの生`extends`文字列を[ExtendsRef]へ変換する唯一の経路のテスト（ADR-0009）。
 *
 * 「保存された参照 == DSLソースをパースした結果」というADR-0009の保証は、
 * [PromptDslParser]で実際にDSLをパースして得た生`extends`文字列を本Mapperに通した結果が
 * 期待する[ExtendsRef]と一致することを検証するラウンドトリップテストで固定する。
 */
class ExtendsFieldMapperTest {
    private val parser = PromptDslParser()

    @Test
    fun `parse はnullをExtendsRefなしとして解釈する`() {
        ExtendsFieldMapper.parse(null) shouldBe null
    }

    @Test
    fun `parse はVersion範囲を伴わないextends文字列をLatestとして解釈する`() {
        ExtendsFieldMapper.parse("templates/base-assistant") shouldBe
            ExtendsRef(TemplateKey("templates/base-assistant"), VersionRange.Latest)
    }

    @Test
    fun `parse はキャレット記法のVersion範囲を解釈する`() {
        ExtendsFieldMapper.parse("templates/base-assistant@^2") shouldBe
            ExtendsRef(TemplateKey("templates/base-assistant"), VersionRange.CaretMajor(2))
    }

    @Test
    fun `parse は完全なSemVerのVersion範囲を解釈する`() {
        ExtendsFieldMapper.parse("templates/base-assistant@1.3.0") shouldBe
            ExtendsRef(TemplateKey("templates/base-assistant"), VersionRange.Exact(SemVer(1, 3, 0)))
    }

    @Test
    fun `parse は文字列以外の値にIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> { ExtendsFieldMapper.parse(42) }
    }

    @Test
    fun `ラウンドトリップ DSLソースをパースしたextends文字列をMapperに通すと期待するExtendsRefになる`() {
        val source =
            """
            ---
            pe: "1"
            kind: prompt
            key: support/faq-answer
            name: FAQ回答生成
            extends: templates/base-assistant@^2
            ---
            {{#block user}}hello{{/block}}
            """.trimIndent()

        val document = parser.parse(source)
        val rawExtends = document.frontMatter.fields["extends"]

        ExtendsFieldMapper.parse(rawExtends) shouldBe
            ExtendsRef(TemplateKey("templates/base-assistant"), VersionRange.CaretMajor(2))
    }

    @Test
    fun `ラウンドトリップ extendsフィールドが無いDSLソースはnullになる`() {
        val source =
            """
            ---
            pe: "1"
            kind: prompt
            key: support/faq-answer
            name: FAQ回答生成
            ---
            {{#block user}}hello{{/block}}
            """.trimIndent()

        val document = parser.parse(source)

        ExtendsFieldMapper.parse(document.frontMatter.fields["extends"]) shouldBe null
    }
}
