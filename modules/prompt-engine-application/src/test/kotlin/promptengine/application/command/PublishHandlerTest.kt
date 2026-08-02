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
import promptengine.domain.fragment.FragmentKey
import promptengine.domain.fragment.FragmentRepository
import promptengine.domain.fragment.NewFragmentVersion
import promptengine.domain.prompt.InvalidStateTransitionException
import promptengine.domain.prompt.NewPromptVersion
import promptengine.domain.prompt.Prompt
import promptengine.domain.prompt.PromptContent
import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.SemVer
import promptengine.domain.template.NewTemplateVersion
import promptengine.domain.template.Template
import promptengine.domain.template.TemplateContent
import promptengine.domain.template.TemplateKey
import promptengine.domain.template.TemplateRepository
import java.time.Instant

/**
 * `publish`ガード「依存先が全てPublished」（設計書§2.5）を、ハンドラ経由（Aggregate単体テストではなく）
 * で検証する（P9bレビュー方針: 評価元データを操作してガードが偽になるケースを作る）。
 */
class PublishHandlerTest {
    private val promptKey = PromptKey("team/greeting")
    private val templateKey = TemplateKey("team/base")
    private val semVer = SemVer(1, 0, 0)
    private val context =
        EventContext(actor = "tester", traceId = "trace-1", occurredAt = Instant.parse("2026-01-01T00:00:00Z"))

    private class FakeDependencyRepository(private val edges: List<DependencyEdge>) : DependencyRepository {
        override fun findOutbound(promptKey: PromptKey): List<DependencyEdge> = edges

        override fun findOutbound(
            promptKey: PromptKey,
            semVer: SemVer,
        ): List<DependencyEdge> = edges

        override fun findInbound(promptKey: PromptKey): List<DependencyEdge> = emptyList()

        override fun replaceOutbound(
            promptKey: PromptKey,
            semVer: SemVer,
            edges: List<DependencyEdge>,
        ) = Unit
    }

    private class InMemoryTemplateRepository : TemplateRepository {
        private val store = mutableMapOf<TemplateKey, Template>()

        fun seed(template: Template) {
            store[template.key] = template
        }

        override fun findByKey(key: TemplateKey): Template? = store[key]

        override fun save(template: Template): Template {
            store[template.key] = template
            return template
        }
    }

    private class InMemoryFragmentRepository : FragmentRepository {
        private val store = mutableMapOf<FragmentKey, Fragment>()

        fun seed(fragment: Fragment) {
            store[fragment.key] = fragment
        }

        override fun findByKey(key: FragmentKey): Fragment? = store[key]

        override fun save(fragment: Fragment): Fragment {
            store[fragment.key] = fragment
            return fragment
        }
    }

    /** Draft→InReview→Approvedまで公開APIで遷移させたPromptを用意する（`internal`コンストラクタを使わない）。 */
    private fun approvedPrompt(): Prompt {
        val newVersion = NewPromptVersion(semVer = semVer, content = PromptContent("body"))
        val (created, _) = Prompt.create(promptKey, newVersion, context)
        val inReview = created.submitForReview(semVer, validationPassed = true)
        return inReview.approve(semVer, approvalCount = 1, requiredApprovalCount = 1)
    }

    private fun publishedDependencyPrompt(key: PromptKey): Prompt {
        val (created, _) = Prompt.create(key, NewPromptVersion(semVer, PromptContent("dep body")), context)
        val approved = created.submitForReview(semVer, validationPassed = true).approve(semVer, 1, 1)
        return approved.publish(semVer, allDependenciesPublished = true, context).first
    }

    private fun handlerWith(
        templateRepository: TemplateRepository = InMemoryTemplateRepository(),
        fragmentRepository: FragmentRepository = InMemoryFragmentRepository(),
        dependencyRepository: DependencyRepository,
        promptRepository: InMemoryPromptRepository,
    ): PublishHandler =
        PublishHandler(
            promptRepository = promptRepository,
            templateRepository = templateRepository,
            fragmentRepository = fragmentRepository,
            dependencyRepository = dependencyRepository,
            idempotentCommandExecutor = PassthroughIdempotentCommandExecutor(),
        )

    @Test
    fun `publish is blocked when a TEMPLATE dependency is not Published`() {
        val promptRepository = InMemoryPromptRepository().apply { seed(approvedPrompt()) }
        val templateRepository =
            InMemoryTemplateRepository().apply {
                seed(Template.create(templateKey, NewTemplateVersion(SemVer(1, 0, 0), TemplateContent("t"))))
            }
        val dependencyRepository =
            FakeDependencyRepository(
                listOf(DependencyEdge(promptKey, semVer, DependencyKind.TEMPLATE, templateKey.value, null)),
            )
        val handler =
            handlerWith(
                templateRepository = templateRepository,
                dependencyRepository = dependencyRepository,
                promptRepository = promptRepository,
            )

        shouldThrow<InvalidStateTransitionException> {
            handler.handle(PublishCommand(promptKey, semVer, actor = "tester", traceId = "trace-1"))
        }
        promptRepository.savedEvents shouldBe emptyList()
    }

    @Test
    fun `publish succeeds when all TEMPLATE dependencies are Published`() {
        val promptRepository = InMemoryPromptRepository().apply { seed(approvedPrompt()) }
        val templateRepository =
            InMemoryTemplateRepository().apply {
                val template = Template.create(templateKey, NewTemplateVersion(SemVer(1, 0, 0), TemplateContent("t")))
                seed(template.publish(SemVer(1, 0, 0)))
            }
        val dependencyRepository =
            FakeDependencyRepository(
                listOf(DependencyEdge(promptKey, semVer, DependencyKind.TEMPLATE, templateKey.value, null)),
            )
        val handler =
            handlerWith(
                templateRepository = templateRepository,
                dependencyRepository = dependencyRepository,
                promptRepository = promptRepository,
            )

        val result = handler.handle(PublishCommand(promptKey, semVer, actor = "tester", traceId = "trace-1"))

        result.key shouldBe promptKey
        result.semVer shouldBe semVer
        promptRepository.savedEvents.size shouldBe 1
    }

    @Test
    fun `publish is blocked when a TEMPLATE dependency does not exist`() {
        val promptRepository = InMemoryPromptRepository().apply { seed(approvedPrompt()) }
        val dependencyRepository =
            FakeDependencyRepository(
                listOf(DependencyEdge(promptKey, semVer, DependencyKind.TEMPLATE, templateKey.value, null)),
            )
        val handler = handlerWith(dependencyRepository = dependencyRepository, promptRepository = promptRepository)

        shouldThrow<InvalidStateTransitionException> {
            handler.handle(PublishCommand(promptKey, semVer, actor = "tester", traceId = "trace-1"))
        }
    }

    @Test
    fun `publish is blocked when a FRAGMENT dependency is not Published`() {
        val fragmentKey = FragmentKey("team/snippet")
        val promptRepository = InMemoryPromptRepository().apply { seed(approvedPrompt()) }
        val fragmentRepository =
            InMemoryFragmentRepository().apply {
                seed(Fragment.create(fragmentKey, NewFragmentVersion(SemVer(1, 0, 0), FragmentContent("f"))))
            }
        val dependencyRepository =
            FakeDependencyRepository(
                listOf(DependencyEdge(promptKey, semVer, DependencyKind.FRAGMENT, fragmentKey.value, null)),
            )
        val handler =
            handlerWith(
                fragmentRepository = fragmentRepository,
                dependencyRepository = dependencyRepository,
                promptRepository = promptRepository,
            )

        shouldThrow<InvalidStateTransitionException> {
            handler.handle(PublishCommand(promptKey, semVer, actor = "tester", traceId = "trace-1"))
        }
    }

    @Test
    fun `publish succeeds when a FRAGMENT dependency is Published`() {
        val fragmentKey = FragmentKey("team/snippet")
        val promptRepository = InMemoryPromptRepository().apply { seed(approvedPrompt()) }
        val fragmentRepository =
            InMemoryFragmentRepository().apply {
                val fragment = Fragment.create(fragmentKey, NewFragmentVersion(SemVer(1, 0, 0), FragmentContent("f")))
                seed(fragment.publish(SemVer(1, 0, 0)))
            }
        val dependencyRepository =
            FakeDependencyRepository(
                listOf(DependencyEdge(promptKey, semVer, DependencyKind.FRAGMENT, fragmentKey.value, null)),
            )
        val handler =
            handlerWith(
                fragmentRepository = fragmentRepository,
                dependencyRepository = dependencyRepository,
                promptRepository = promptRepository,
            )

        val result = handler.handle(PublishCommand(promptKey, semVer, actor = "tester", traceId = "trace-1"))

        result.key shouldBe promptKey
    }

    @Test
    fun `publish is blocked when a PROMPT dependency is not Published`() {
        val dependencyPromptKey = PromptKey("team/other")
        val promptRepository =
            InMemoryPromptRepository().apply {
                seed(approvedPrompt())
                val (dependency, _) =
                    Prompt.create(
                        dependencyPromptKey,
                        NewPromptVersion(semVer, PromptContent("dep")),
                        context,
                    )
                seed(dependency)
            }
        val dependencyRepository =
            FakeDependencyRepository(
                listOf(DependencyEdge(promptKey, semVer, DependencyKind.PROMPT, dependencyPromptKey.value, null)),
            )
        val handler = handlerWith(dependencyRepository = dependencyRepository, promptRepository = promptRepository)

        shouldThrow<InvalidStateTransitionException> {
            handler.handle(PublishCommand(promptKey, semVer, actor = "tester", traceId = "trace-1"))
        }
    }

    @Test
    fun `publish succeeds when a PROMPT dependency is Published`() {
        val dependencyPromptKey = PromptKey("team/other")
        val promptRepository =
            InMemoryPromptRepository().apply {
                seed(approvedPrompt())
                seed(publishedDependencyPrompt(dependencyPromptKey))
            }
        val dependencyRepository =
            FakeDependencyRepository(
                listOf(DependencyEdge(promptKey, semVer, DependencyKind.PROMPT, dependencyPromptKey.value, null)),
            )
        val handler = handlerWith(dependencyRepository = dependencyRepository, promptRepository = promptRepository)

        val result = handler.handle(PublishCommand(promptKey, semVer, actor = "tester", traceId = "trace-1"))

        result.key shouldBe promptKey
    }

    @Test
    fun `publish is blocked when a FRAGMENT dependency does not exist`() {
        val promptRepository = InMemoryPromptRepository().apply { seed(approvedPrompt()) }
        val dependencyRepository =
            FakeDependencyRepository(
                listOf(DependencyEdge(promptKey, semVer, DependencyKind.FRAGMENT, "team/missing", null)),
            )
        val handler = handlerWith(dependencyRepository = dependencyRepository, promptRepository = promptRepository)

        shouldThrow<InvalidStateTransitionException> {
            handler.handle(PublishCommand(promptKey, semVer, actor = "tester", traceId = "trace-1"))
        }
    }

    @Test
    fun `publish is blocked when a PROMPT dependency does not exist`() {
        val promptRepository = InMemoryPromptRepository().apply { seed(approvedPrompt()) }
        val dependencyRepository =
            FakeDependencyRepository(
                listOf(DependencyEdge(promptKey, semVer, DependencyKind.PROMPT, "team/missing", null)),
            )
        val handler = handlerWith(dependencyRepository = dependencyRepository, promptRepository = promptRepository)

        shouldThrow<InvalidStateTransitionException> {
            handler.handle(PublishCommand(promptKey, semVer, actor = "tester", traceId = "trace-1"))
        }
    }

    @Test
    fun `publish is blocked when the Published TEMPLATE version does not satisfy the version range`() {
        val promptRepository = InMemoryPromptRepository().apply { seed(approvedPrompt()) }
        val templateRepository =
            InMemoryTemplateRepository().apply {
                // ^2（major 2系）を要求するが、実際にPublished済みなのはmajor 1系のみ。
                val template = Template.create(templateKey, NewTemplateVersion(SemVer(1, 0, 0), TemplateContent("t")))
                seed(template.publish(SemVer(1, 0, 0)))
            }
        val dependencyRepository =
            FakeDependencyRepository(
                listOf(DependencyEdge(promptKey, semVer, DependencyKind.TEMPLATE, templateKey.value, "^2")),
            )
        val handler =
            handlerWith(
                templateRepository = templateRepository,
                dependencyRepository = dependencyRepository,
                promptRepository = promptRepository,
            )

        shouldThrow<InvalidStateTransitionException> {
            handler.handle(PublishCommand(promptKey, semVer, actor = "tester", traceId = "trace-1"))
        }
    }

    @Test
    fun `publish succeeds vacuously when there are no dependencies`() {
        val promptRepository = InMemoryPromptRepository().apply { seed(approvedPrompt()) }
        val handler =
            handlerWith(
                dependencyRepository = FakeDependencyRepository(emptyList()),
                promptRepository = promptRepository,
            )

        val result = handler.handle(PublishCommand(promptKey, semVer, actor = "tester", traceId = "trace-1"))

        result.key shouldBe promptKey
    }
}
