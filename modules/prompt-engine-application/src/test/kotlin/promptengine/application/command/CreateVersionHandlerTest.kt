package promptengine.application.command

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.dependency.DependencyEdge
import promptengine.domain.dependency.DependencyKind
import promptengine.domain.dependency.DependencyRepository
import promptengine.domain.event.EventContext
import promptengine.domain.fragment.Fragment
import promptengine.domain.fragment.FragmentContent
import promptengine.domain.fragment.FragmentDomainEvent
import promptengine.domain.fragment.FragmentKey
import promptengine.domain.fragment.FragmentRepository
import promptengine.domain.fragment.NewFragmentVersion
import promptengine.domain.prompt.NewPromptVersion
import promptengine.domain.prompt.Prompt
import promptengine.domain.prompt.PromptContent
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.PromptVersionNotFoundException
import promptengine.domain.shared.SemVer
import promptengine.domain.template.NewTemplateVersion
import promptengine.domain.template.Template
import promptengine.domain.template.TemplateContent
import promptengine.domain.template.TemplateDomainEvent
import promptengine.domain.template.TemplateKey
import promptengine.domain.template.TemplateRepository
import promptengine.engine.compiler.CompositionServiceImpl
import promptengine.engine.compiler.ExtendsFieldResolverImpl
import java.time.Instant

/**
 * Issue #21: Version作成コマンドは呼び出し側から`ExtendsRef`を個別引数として受け取らず、必ず
 * [ExtendsFieldResolverImpl]（実装、`prompt-engine-core`）を通して`content.source`から導出する。
 * 「保存された`ExtendsRef` == `content.source`をパースした結果」を検証する。
 *
 * ADR-0033決定3: `dependencies`への書き込みは`CompositionService.compile`（COMPILE_ONLY）が返す
 * 平坦化済み依存フルセットから行う。extends由来のTEMPLATE依存だけでなく、本文中の
 * `{{> }}`によるFRAGMENT依存も書き込まれることを検証する（P9b時点の既知の欠落の回帰テスト）。
 */
class CreateVersionHandlerTest {
    private val promptKey = PromptKey("support/faq-answer")
    private val existingSemVer = SemVer(1, 0, 0)
    private val newSemVer = SemVer(1, 1, 0)
    private val templateKey = TemplateKey("templates/base-assistant")
    private val fragmentKey = FragmentKey("fragments/shared-notice")

    private val source =
        """
        ---
        pe: "1"
        kind: prompt
        key: support/faq-answer
        name: FAQ回答生成
        extends: templates/base-assistant@^2
        ---
        {{#block user}}hello {{> fragments/shared-notice@1.0.0 }}{{/block}}
        """.trimIndent()

    private class CapturingDependencyRepository : DependencyRepository {
        val captured = mutableListOf<DependencyEdge>()

        override fun findOutbound(promptKey: PromptKey): List<DependencyEdge> = emptyList()

        override fun findOutbound(
            promptKey: PromptKey,
            semVer: SemVer,
        ): List<DependencyEdge> = emptyList()

        override fun findInbound(promptKey: PromptKey): List<DependencyEdge> = emptyList()

        override fun findInboundTemplateOrFragment(
            kind: DependencyKind,
            key: String,
        ): List<DependencyEdge> = emptyList()

        override fun replaceOutbound(
            promptKey: PromptKey,
            semVer: SemVer,
            edges: List<DependencyEdge>,
        ) {
            captured += edges
        }
    }

    private class FakeTemplateRepository : TemplateRepository {
        private val templates = mutableMapOf<TemplateKey, Template>()
        private val context =
            EventContext(actor = "tester", traceId = "trace-1", occurredAt = Instant.parse("2026-01-01T00:00:00Z"))

        fun addPublished(
            key: TemplateKey,
            semVer: SemVer,
            bodyText: String,
        ) {
            val body = "---\npe: \"1\"\nkind: template\nkey: ${key.value}\n---\n$bodyText"
            val created = Template.create(key, NewTemplateVersion(semVer, TemplateContent(body)), context).first
            templates[key] = created.publish(semVer, context).first
        }

        override fun findByKey(key: TemplateKey): Template? = templates[key]

        override fun save(
            template: Template,
            events: List<TemplateDomainEvent>,
        ): Template {
            templates[template.key] = template
            return template
        }
    }

    private class FakeFragmentRepository : FragmentRepository {
        private val fragments = mutableMapOf<FragmentKey, Fragment>()
        private val context =
            EventContext(actor = "tester", traceId = "trace-1", occurredAt = Instant.parse("2026-01-01T00:00:00Z"))

        fun addPublished(
            key: FragmentKey,
            semVer: SemVer,
            bodyText: String,
        ) {
            val body = "---\npe: \"1\"\nkind: fragment\nkey: ${key.value}\n---\n$bodyText"
            val created = Fragment.create(key, NewFragmentVersion(semVer, FragmentContent(body)), context).first
            fragments[key] = created.publish(semVer, context).first
        }

        override fun findByKey(key: FragmentKey): Fragment? = fragments[key]

        override fun save(
            fragment: Fragment,
            events: List<FragmentDomainEvent>,
        ): Fragment {
            fragments[fragment.key] = fragment
            return fragment
        }
    }

    private fun newHandler(
        promptRepository: InMemoryPromptRepository,
        dependencyRepository: CapturingDependencyRepository,
        templateRepository: FakeTemplateRepository = FakeTemplateRepository(),
        fragmentRepository: FakeFragmentRepository = FakeFragmentRepository(),
    ): CreateVersionHandler =
        CreateVersionHandler(
            promptRepository,
            dependencyRepository,
            ExtendsFieldResolverImpl(),
            CompositionServiceImpl(templateRepository, fragmentRepository),
            PassthroughIdempotentCommandExecutor(),
        )

    @Test
    fun `保存されたExtendsRefはcontent_sourceをExtendsFieldResolverで直接解決した結果と一致する`() {
        val context =
            EventContext(actor = "tester", traceId = "trace-1", occurredAt = Instant.parse("2026-01-01T00:00:00Z"))
        val existingVersion = NewPromptVersion(semVer = existingSemVer, content = PromptContent("original body"))
        val (seedPrompt, _) = Prompt.create(promptKey, existingVersion, context)
        val promptRepository = InMemoryPromptRepository().apply { seed(seedPrompt) }
        val dependencyRepository = CapturingDependencyRepository()
        val templateRepository = FakeTemplateRepository().apply { addPublished(templateKey, SemVer(2, 0, 0), "") }
        val fragmentRepository = FakeFragmentRepository().apply { addPublished(fragmentKey, SemVer(1, 0, 0), "hi") }
        val resolver = ExtendsFieldResolverImpl()
        val handler = newHandler(promptRepository, dependencyRepository, templateRepository, fragmentRepository)

        handler.handle(
            CreateVersionCommand(promptKey, newSemVer, source, actor = "tester", traceId = "trace-1"),
        )

        val expectedExtends = resolver.resolve(source)
        val savedVersion = promptRepository.findByKey(promptKey)!!.versions.find { it.semVer == newSemVer }!!
        savedVersion.extends shouldBe expectedExtends

        dependencyRepository.captured.size shouldBe 2
        val templateEdge = dependencyRepository.captured.single { it.toKind == DependencyKind.TEMPLATE }
        templateEdge.toKey shouldBe expectedExtends!!.key.value
        templateEdge.toVersion shouldBe "^2"
        val fragmentEdge = dependencyRepository.captured.single { it.toKind == DependencyKind.FRAGMENT }
        fragmentEdge.toKey shouldBe fragmentKey.value
        fragmentEdge.toVersion shouldBe "1.0.0"
    }

    @Test
    fun `Promptが存在しなければ例外を投げる`() {
        val handler = newHandler(InMemoryPromptRepository(), CapturingDependencyRepository())

        shouldThrow<PromptVersionNotFoundException> {
            handler.handle(CreateVersionCommand(promptKey, newSemVer, source, actor = "tester", traceId = "trace-1"))
        }
    }
}
