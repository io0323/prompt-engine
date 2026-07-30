package promptengine.engine.compiler

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.composition.CompositionMode
import promptengine.domain.composition.MacroNotFoundException
import promptengine.domain.composition.ResolvedDependency
import promptengine.domain.event.EventContext
import promptengine.domain.fragment.Fragment
import promptengine.domain.fragment.FragmentContent
import promptengine.domain.fragment.FragmentKey
import promptengine.domain.fragment.FragmentRepository
import promptengine.domain.fragment.NewFragmentVersion
import promptengine.domain.prompt.NewPromptVersion
import promptengine.domain.prompt.Prompt
import promptengine.domain.prompt.PromptContent
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.PromptVersion
import promptengine.domain.shared.ExtendsRefApi
import promptengine.domain.shared.SemVer
import promptengine.domain.shared.VersionRange
import promptengine.domain.template.ExtendsRef
import promptengine.domain.template.NewTemplateVersion
import promptengine.domain.template.Template
import promptengine.domain.template.TemplateContent
import promptengine.domain.template.TemplateKey
import promptengine.domain.template.TemplateRepository
import promptengine.domain.template.ast.BlockNode
import promptengine.domain.template.ast.BlockRole
import promptengine.domain.template.ast.ExprNode
import promptengine.domain.template.ast.Expression
import promptengine.domain.template.ast.StringLiteral
import promptengine.domain.template.ast.TextNode
import promptengine.domain.validation.PlaceholderMode
import promptengine.domain.validation.ValidationSettings
import promptengine.domain.variable.VariableDefinition
import promptengine.domain.variable.VariableType
import java.time.Instant

/**
 * [CompositionServiceImpl]の結合テスト（設計書§15.3〜§15.6、ADR-0009/ADR-0010）。
 * extends → import → include → macro展開の解決順が正しく結線され、super()の解決が
 * extendsマージ段階で行われることを確認する。
 */
@OptIn(ExtendsRefApi::class)
class CompositionServiceImplTest {
    private val eventContext = EventContext(actor = "user:test", traceId = "trace-1", occurredAt = Instant.EPOCH)

    @Test
    fun `extends importなし の単純なPromptはそのまま合成される`() {
        val templateRepo = FakeTemplateRepository()
        val fragmentRepo = FakeFragmentRepository()
        val service = CompositionServiceImpl(templateRepo, fragmentRepo)
        val promptKey = PromptKey("support/faq")
        val promptVersion = promptVersion(promptKey, "{{#block user}}hello{{/block}}")

        val result = service.compile(promptKey, promptVersion, CompositionMode.STANDARD)

        result.body shouldBe listOf(BlockNode(BlockRole.USER, listOf(TextNode("hello"))))
        result.dependencies shouldBe emptyList()
        result.variables shouldBe emptyList()
        result.validation shouldBe ValidationSettings()
    }

    @Test
    fun `PromptVersionのvalidationはそのままCompiledPromptへ引き継がれる ADR-0012`() {
        val templateRepo = FakeTemplateRepository()
        val fragmentRepo = FakeFragmentRepository()
        val service = CompositionServiceImpl(templateRepo, fragmentRepo)
        val promptKey = PromptKey("support/faq")
        val settings = ValidationSettings(maxLength = 32000, placeholders = PlaceholderMode.STRICT)
        val source = wrap(promptKey.value, "prompt", "hello")
        val newVersion = NewPromptVersion(SemVer(1, 0, 0), PromptContent(source), validation = settings)
        val promptVersion = Prompt.create(promptKey, newVersion, eventContext).first.versions.first()

        val result = service.compile(promptKey, promptVersion, CompositionMode.STANDARD)

        result.validation shouldBe settings
    }

    @Test
    fun `extends・import・include・macro・super を組み合わせて合成する`() {
        val templateRepo = FakeTemplateRepository()
        val fragmentRepo = FakeFragmentRepository()
        fragmentRepo.addPublished(FragmentKey("fragments/safety"), SemVer(1, 0, 0), "policy-text")
        templateRepo.addPublished(
            TemplateKey("templates/base"),
            SemVer(1, 0, 0),
            """
            {{#block system}}base-system{{/block}}
            {{#block user}}base-user{{/block}}
            """.trimIndent(),
        )
        val service = CompositionServiceImpl(templateRepo, fragmentRepo)
        val promptKey = PromptKey("support/faq")
        val source =
            """
            ---
            pe: "1"
            kind: prompt
            key: support/faq
            imports:
              - alias: safety
                ref: fragments/safety
            macros:
              - name: shout
                params: [text]
                body: '{{ text }}!!!'
            ---
            {{#block system}}{{ super() }} {{> safety }}{{/block}}
            {{#block user}}{{ shout(text="hi") }}{{/block}}
            """.trimIndent()
        val promptVersion =
            promptVersionFromSource(promptKey, source, ExtendsRef(TemplateKey("templates/base")))

        val result = service.compile(promptKey, promptVersion, CompositionMode.STANDARD)

        result.body shouldBe
            listOf(
                BlockNode(BlockRole.SYSTEM, listOf(TextNode("base-system"), TextNode(" "), TextNode("policy-text"))),
                TextNode("\n"),
                BlockNode(BlockRole.USER, listOf(ExprNode(Expression(StringLiteral("hi"))), TextNode("!!!"))),
            )
        result.dependencies shouldBe
            listOf(
                ResolvedDependency.TemplateDependency(
                    TemplateKey("templates/base"),
                    VersionRange.Latest,
                    SemVer(1, 0, 0),
                    promptengine.domain.shared.PublicationState.Published,
                    templateRepo.contentHashOf(TemplateKey("templates/base"), SemVer(1, 0, 0)),
                ),
                ResolvedDependency.FragmentDependency(
                    FragmentKey("fragments/safety"),
                    VersionRange.Latest,
                    SemVer(1, 0, 0),
                    promptengine.domain.shared.PublicationState.Published,
                    fragmentRepo.contentHashOf(FragmentKey("fragments/safety"), SemVer(1, 0, 0)),
                ),
            )
    }

    @Test
    fun `決定性 同一入力から常に同じCompiledPromptが得られる`() {
        val templateRepo = FakeTemplateRepository()
        val fragmentRepo = FakeFragmentRepository()
        fragmentRepo.addPublished(FragmentKey("fragments/safety"), SemVer(1, 0, 0), "policy")
        val service = CompositionServiceImpl(templateRepo, fragmentRepo)
        val promptKey = PromptKey("support/faq")
        val promptVersion = promptVersion(promptKey, "{{#block user}}{{> fragments/safety }}{{/block}}")

        val first = service.compile(promptKey, promptVersion, CompositionMode.STANDARD)
        val second = service.compile(promptKey, promptVersion, CompositionMode.STANDARD)

        first shouldBe second
    }

    @Test
    fun `変数はリーフPrompt自身よりextendsチェーンよりFragmentの順で名前重複排除してマージされる`() {
        val templateRepo = FakeTemplateRepository()
        val fragmentRepo = FakeFragmentRepository()
        fragmentRepo.addPublished(
            FragmentKey("fragments/frag"),
            SemVer(1, 0, 0),
            "frag-body",
            variables =
                listOf(
                    VariableDefinition("b", VariableType.NUMBER),
                    VariableDefinition("c", VariableType.STRING),
                ),
        )
        templateRepo.addPublished(
            TemplateKey("templates/base"),
            SemVer(1, 0, 0),
            "{{#block system}}sys{{/block}}",
            variables =
                listOf(
                    VariableDefinition("a", VariableType.NUMBER),
                    VariableDefinition("b", VariableType.STRING),
                ),
        )
        val service = CompositionServiceImpl(templateRepo, fragmentRepo)
        val promptKey = PromptKey("support/faq")
        val source = "{{#block user}}{{> fragments/frag }}{{/block}}"
        val promptVersion =
            promptVersion(
                promptKey,
                source,
                extends = ExtendsRef(TemplateKey("templates/base")),
                variables = listOf(VariableDefinition("a", VariableType.STRING)),
            )

        val result = service.compile(promptKey, promptVersion, CompositionMode.STANDARD)

        result.variables shouldBe
            listOf(
                VariableDefinition("a", VariableType.STRING),
                VariableDefinition("b", VariableType.STRING),
                VariableDefinition("c", VariableType.STRING),
            )
    }

    @Test
    fun `Fragment内のmacro呼出は呼出元Promptのmacroで解決されない`() {
        val templateRepo = FakeTemplateRepository()
        val fragmentRepo = FakeFragmentRepository()
        fragmentRepo.addPublished(FragmentKey("fragments/broken"), SemVer(1, 0, 0), """{{ shout(text="hi") }}""")
        val service = CompositionServiceImpl(templateRepo, fragmentRepo)
        val promptKey = PromptKey("support/faq")
        val source =
            """
            ---
            pe: "1"
            kind: prompt
            key: support/faq
            macros:
              - name: shout
                params: [text]
                body: '{{ text }}!!!'
            ---
            {{#block user}}{{> fragments/broken }}{{/block}}
            """.trimIndent()
        val promptVersion = promptVersionFromSource(promptKey, source)

        val exception =
            shouldThrow<MacroNotFoundException> {
                service.compile(promptKey, promptVersion, CompositionMode.STANDARD)
            }
        exception.macroName shouldBe "shout"
    }

    @Test
    fun `親テンプレートで定義したmacroはsuperで差し込まれた親由来の内容の中で展開される`() {
        val templateRepo = FakeTemplateRepository()
        val fragmentRepo = FakeFragmentRepository()
        templateRepo.addPublishedFromSource(
            TemplateKey("templates/base"),
            SemVer(1, 0, 0),
            """
            ---
            pe: "1"
            kind: template
            key: templates/base
            macros:
              - name: shout
                params: [text]
                body: '{{ text }}!!!'
            ---
            {{#block system}}{{ shout(text="hi") }}{{/block}}
            """.trimIndent(),
        )
        val service = CompositionServiceImpl(templateRepo, fragmentRepo)
        val promptKey = PromptKey("support/faq")
        val promptVersion =
            promptVersion(
                promptKey,
                "{{#block system}}{{ super() }}{{/block}}",
                extends = ExtendsRef(TemplateKey("templates/base")),
            )

        val result = service.compile(promptKey, promptVersion, CompositionMode.STANDARD)

        result.body shouldBe
            listOf(BlockNode(BlockRole.SYSTEM, listOf(ExprNode(Expression(StringLiteral("hi"))), TextNode("!!!"))))
    }

    @Test
    fun `子で同名macroを再定義しても既に確定した親由来の内容には影響しない`() {
        val templateRepo = FakeTemplateRepository()
        val fragmentRepo = FakeFragmentRepository()
        templateRepo.addPublishedFromSource(
            TemplateKey("templates/base"),
            SemVer(1, 0, 0),
            """
            ---
            pe: "1"
            kind: template
            key: templates/base
            macros:
              - name: shout
                params: [text]
                body: '{{ text }}!!!'
            ---
            {{#block system}}{{ shout(text="hi") }}{{/block}}
            """.trimIndent(),
        )
        val service = CompositionServiceImpl(templateRepo, fragmentRepo)
        val promptKey = PromptKey("support/faq")
        val source =
            """
            ---
            pe: "1"
            kind: prompt
            key: support/faq
            macros:
              - name: shout
                params: [text]
                body: '{{ text }}???'
            ---
            {{#block system}}{{ super() }}-{{ shout(text="bye") }}{{/block}}
            """.trimIndent()
        val promptVersion = promptVersionFromSource(promptKey, source, ExtendsRef(TemplateKey("templates/base")))

        val result = service.compile(promptKey, promptVersion, CompositionMode.STANDARD)

        // superで差し込まれた"hi!!!"は親自身のshout（!!!）のまま、子の呼出"bye"だけが子自身のshout（???）で
        // 展開される。同名macroに「優先順位」は存在せず、呼出が書かれた宣言単位自身のmacroで解決される
        // （c: 親子で同名macroを定義した場合の実際の挙動）。
        result.body shouldBe
            listOf(
                BlockNode(
                    BlockRole.SYSTEM,
                    listOf(
                        ExprNode(Expression(StringLiteral("hi"))),
                        TextNode("!!!"),
                        TextNode("-"),
                        ExprNode(Expression(StringLiteral("bye"))),
                        TextNode("???"),
                    ),
                ),
            )
    }

    @Test
    fun `ブロック外に残ったsuper呼出は未定義macro扱いになる`() {
        val templateRepo = FakeTemplateRepository()
        val fragmentRepo = FakeFragmentRepository()
        val service = CompositionServiceImpl(templateRepo, fragmentRepo)
        val promptKey = PromptKey("support/faq")
        val promptVersion = promptVersion(promptKey, "{{ super() }}")

        val exception =
            shouldThrow<MacroNotFoundException> {
                service.compile(promptKey, promptVersion, CompositionMode.STANDARD)
            }
        exception.macroName shouldBe "super"
    }

    /** [bodyText]（本文のみ）を標準のフロントマターで包んで[PromptVersion]を作る。 */
    private fun promptVersion(
        key: PromptKey,
        bodyText: String,
        extends: ExtendsRef? = null,
        variables: List<VariableDefinition> = emptyList(),
    ): PromptVersion {
        val source = wrap(key.value, "prompt", bodyText)
        val newVersion =
            NewPromptVersion(SemVer(1, 0, 0), PromptContent(source), variables = variables, extends = extends)
        return Prompt.create(key, newVersion, eventContext).first.versions.first()
    }

    /** [fullSource]（フロントマター込みの完全なDSLソース）から[PromptVersion]を作る。 */
    private fun promptVersionFromSource(
        key: PromptKey,
        fullSource: String,
        extends: ExtendsRef? = null,
    ): PromptVersion {
        val newVersion = NewPromptVersion(SemVer(1, 0, 0), PromptContent(fullSource), extends = extends)
        return Prompt.create(key, newVersion, eventContext).first.versions.first()
    }

    private class FakeTemplateRepository : TemplateRepository {
        private val templates = mutableMapOf<TemplateKey, Template>()

        fun addPublished(
            key: TemplateKey,
            semVer: SemVer,
            bodyText: String,
            variables: List<VariableDefinition> = emptyList(),
        ) = addPublishedFromSource(key, semVer, wrap(key.value, "template", bodyText), variables)

        /** `macros:`/`imports:`など、フロントマターを自前で指定したい場合に使う。 */
        fun addPublishedFromSource(
            key: TemplateKey,
            semVer: SemVer,
            fullSource: String,
            variables: List<VariableDefinition> = emptyList(),
        ) {
            val newVersion = NewTemplateVersion(semVer, TemplateContent(fullSource), variables)
            var template = templates[key]?.newVersion(newVersion) ?: Template.create(key, newVersion)
            template = template.publish(semVer)
            templates[key] = template
        }

        fun contentHashOf(
            key: TemplateKey,
            semVer: SemVer,
        ): String = templates.getValue(key).versions.single { it.semVer == semVer }.content.contentHash

        override fun findByKey(key: TemplateKey): Template? = templates[key]

        override fun save(template: Template): Template {
            templates[template.key] = template
            return template
        }
    }

    private class FakeFragmentRepository : FragmentRepository {
        private val fragments = mutableMapOf<FragmentKey, Fragment>()

        fun addPublished(
            key: FragmentKey,
            semVer: SemVer,
            bodyText: String,
            variables: List<VariableDefinition> = emptyList(),
        ) {
            val source = wrap(key.value, "fragment", bodyText)
            val newVersion = NewFragmentVersion(semVer, FragmentContent(source), variables)
            var fragment = fragments[key]?.newVersion(newVersion) ?: Fragment.create(key, newVersion)
            fragment = fragment.publish(semVer)
            fragments[key] = fragment
        }

        fun contentHashOf(
            key: FragmentKey,
            semVer: SemVer,
        ): String = fragments.getValue(key).versions.single { it.semVer == semVer }.content.contentHash

        override fun findByKey(key: FragmentKey): Fragment? = fragments[key]

        override fun save(fragment: Fragment): Fragment {
            fragments[fragment.key] = fragment
            return fragment
        }
    }

    private companion object {
        fun wrap(
            key: String,
            kind: String,
            bodyText: String,
        ): String =
            """
            ---
            pe: "1"
            kind: $kind
            key: $key
            ---
            $bodyText
            """.trimIndent()
    }
}
