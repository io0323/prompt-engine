package promptengine.engine.compiler

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.composition.CircularDependencyException
import promptengine.domain.composition.CompositionDepthExceededException
import promptengine.domain.composition.CompositionMode
import promptengine.domain.composition.CompositionSizeExceededException
import promptengine.domain.composition.DraftReferenceNotAllowedException
import promptengine.domain.composition.ResolvedDependency
import promptengine.domain.composition.TemplateReferenceNotFoundException
import promptengine.domain.event.EventContext
import promptengine.domain.shared.ExtendsRefApi
import promptengine.domain.shared.PublicationState
import promptengine.domain.shared.SemVer
import promptengine.domain.shared.VersionRange
import promptengine.domain.template.ExtendsRef
import promptengine.domain.template.NewTemplateVersion
import promptengine.domain.template.Template
import promptengine.domain.template.TemplateContent
import promptengine.domain.template.TemplateDomainEvent
import promptengine.domain.template.TemplateKey
import promptengine.domain.template.TemplateRepository
import java.time.Instant

/**
 * [ReferenceResolver]のテスト（ADR-0009）。[FakeTemplateRepository]は固定の内容を持つ
 * 純粋なインメモリ実装であり、モック化フレームワークは使わない（決定性テストの前提を
 * リポジトリ側の振る舞いに依存させないため）。テスト用フィクスチャが[ExtendsRef]を
 * 直接構築するため、クラス単位で[ExtendsRefApi]をOptInする。
 */
@OptIn(ExtendsRefApi::class)
class ReferenceResolverTest {
    @Test
    fun `rootExtendsがnullなら空リストを返す`() {
        val resolver = ReferenceResolver(FakeTemplateRepository())

        resolver.resolveExtendsChain("prompt:support/faq", null, CompositionMode.STANDARD).shouldBeEmpty()
    }

    @Test
    fun `単純なextends1段はPublished版が解決される`() {
        val repo = FakeTemplateRepository()
        repo.addPublished(TemplateKey("templates/base"), SemVer(1, 0, 0), "base body")
        val resolver = ReferenceResolver(repo)

        val result =
            resolver.resolveExtendsChain(
                "prompt:support/faq",
                ExtendsRef(TemplateKey("templates/base"), VersionRange.Latest),
                CompositionMode.STANDARD,
            )

        result shouldBe
            listOf(
                ResolvedDependency.TemplateDependency(
                    key = TemplateKey("templates/base"),
                    requestedRange = VersionRange.Latest,
                    resolvedVersion = SemVer(1, 0, 0),
                    status = PublicationState.Published,
                    contentHash = TemplateContent("base body").contentHash,
                ),
            )
    }

    @Test
    fun `多段継承は鎖の全Versionを解決順に返す`() {
        val repo = FakeTemplateRepository()
        val keyA = TemplateKey("templates/a")
        val keyB = TemplateKey("templates/b")
        val keyC = TemplateKey("templates/c")
        repo.addPublished(keyA, SemVer(1, 0, 0), "a body", extends = ExtendsRef(keyB))
        repo.addPublished(keyB, SemVer(1, 0, 0), "b body", extends = ExtendsRef(keyC))
        repo.addPublished(keyC, SemVer(1, 0, 0), "c body")
        val resolver = ReferenceResolver(repo)

        val result =
            resolver.resolveExtendsChain(
                "prompt:support/faq",
                ExtendsRef(keyA),
                CompositionMode.STANDARD,
            )

        result.map { it.key } shouldBe listOf(keyA, keyB, keyC)
    }

    @Test
    fun `相互参照 A extends B extends A はCircularDependencyExceptionを投げる`() {
        val repo = FakeTemplateRepository()
        val keyA = TemplateKey("templates/a")
        val keyB = TemplateKey("templates/b")
        repo.addPublished(keyA, SemVer(1, 0, 0), "a", extends = ExtendsRef(keyB))
        repo.addPublished(keyB, SemVer(1, 0, 0), "b", extends = ExtendsRef(keyA))
        val resolver = ReferenceResolver(repo)

        shouldThrow<CircularDependencyException> {
            resolver.resolveExtendsChain(
                "prompt:support/faq",
                ExtendsRef(keyA),
                CompositionMode.STANDARD,
            )
        }
    }

    @Test
    fun `間接3段の循環 A extends B extends C extends A はCircularDependencyExceptionを投げる`() {
        val repo = FakeTemplateRepository()
        val keyA = TemplateKey("templates/a")
        val keyB = TemplateKey("templates/b")
        val keyC = TemplateKey("templates/c")
        repo.addPublished(keyA, SemVer(1, 0, 0), "a", extends = ExtendsRef(keyB))
        repo.addPublished(keyB, SemVer(1, 0, 0), "b", extends = ExtendsRef(keyC))
        repo.addPublished(keyC, SemVer(1, 0, 0), "c", extends = ExtendsRef(keyA))
        val resolver = ReferenceResolver(repo)

        val exception =
            shouldThrow<CircularDependencyException> {
                resolver.resolveExtendsChain(
                    "prompt:support/faq",
                    ExtendsRef(keyA),
                    CompositionMode.STANDARD,
                )
            }
        exception.cyclePath shouldBe
            listOf("prompt:support/faq", "templates/a", "templates/b", "templates/c", "templates/a")
    }

    @Test
    fun `深さ上限を超えるとCompositionDepthExceededExceptionを投げる`() {
        val repo = FakeTemplateRepository()
        // ルート(prompt) -> t1 -> t2 -> t3 -> t4 -> t5 -> t6 で深さ上限5(既定)を超える。
        (1..6).forEach { i ->
            val extendsRef = if (i < 6) ExtendsRef(TemplateKey("templates/t${i + 1}")) else null
            repo.addPublished(TemplateKey("templates/t$i"), SemVer(1, 0, 0), "body$i", extends = extendsRef)
        }
        val resolver = ReferenceResolver(repo)

        shouldThrow<CompositionDepthExceededException> {
            resolver.resolveExtendsChain(
                "prompt:support/faq",
                ExtendsRef(TemplateKey("templates/t1")),
                CompositionMode.STANDARD,
            )
        }
    }

    @Test
    fun `展開後サイズ上限を超えるとCompositionSizeExceededExceptionを投げる`() {
        val repo = FakeTemplateRepository()
        repo.addPublished(TemplateKey("templates/big"), SemVer(1, 0, 0), "x".repeat(100))
        val resolver = ReferenceResolver(repo, maxExpandedSizeBytes = 50)

        shouldThrow<CompositionSizeExceededException> {
            resolver.resolveExtendsChain(
                "prompt:support/faq",
                ExtendsRef(TemplateKey("templates/big")),
                CompositionMode.STANDARD,
            )
        }
    }

    @Test
    fun `キャレット範囲は同一majorの最新Publishedを解決する`() {
        val repo = FakeTemplateRepository()
        repo.addPublished(TemplateKey("templates/base"), SemVer(1, 9, 0), "old major")
        repo.addPublished(TemplateKey("templates/base"), SemVer(2, 0, 0), "v2.0.0")
        repo.addPublished(TemplateKey("templates/base"), SemVer(2, 3, 0), "v2.3.0 latest")
        repo.addPublished(TemplateKey("templates/base"), SemVer(3, 0, 0), "next major")
        val resolver = ReferenceResolver(repo)

        val result =
            resolver.resolveExtendsChain(
                "prompt:support/faq",
                ExtendsRef(TemplateKey("templates/base"), VersionRange.CaretMajor(2)),
                CompositionMode.STANDARD,
            )

        result.single().resolvedVersion shouldBe SemVer(2, 3, 0)
    }

    @Test
    fun `完全一致のSemVer範囲はそのVersionのみを解決する`() {
        val repo = FakeTemplateRepository()
        repo.addPublished(TemplateKey("templates/base"), SemVer(1, 3, 0), "1.3.0 body")
        repo.addPublished(TemplateKey("templates/base"), SemVer(1, 4, 0), "1.4.0 body")
        val resolver = ReferenceResolver(repo)

        val result =
            resolver.resolveExtendsChain(
                "prompt:support/faq",
                ExtendsRef(TemplateKey("templates/base"), VersionRange.Exact(SemVer(1, 3, 0))),
                CompositionMode.STANDARD,
            )

        result.single().resolvedVersion shouldBe SemVer(1, 3, 0)
    }

    @Test
    fun `範囲に該当するVersionが無い場合はTemplateReferenceNotFoundExceptionを投げる`() {
        val repo = FakeTemplateRepository()
        repo.addPublished(TemplateKey("templates/base"), SemVer(1, 0, 0), "body")
        val resolver = ReferenceResolver(repo)

        shouldThrow<TemplateReferenceNotFoundException> {
            resolver.resolveExtendsChain(
                "prompt:support/faq",
                ExtendsRef(TemplateKey("templates/base"), VersionRange.CaretMajor(9)),
                CompositionMode.STANDARD,
            )
        }
    }

    @Test
    fun `参照先Templateが存在しない場合はTemplateReferenceNotFoundExceptionを投げる`() {
        val resolver = ReferenceResolver(FakeTemplateRepository())

        shouldThrow<TemplateReferenceNotFoundException> {
            resolver.resolveExtendsChain(
                "prompt:support/faq",
                ExtendsRef(TemplateKey("templates/missing")),
                CompositionMode.STANDARD,
            )
        }
    }

    @Test
    fun `STANDARDモードではDraft版しかない参照はDraftReferenceNotAllowedExceptionを投げる`() {
        val repo = FakeTemplateRepository()
        repo.addDraft(TemplateKey("templates/base"), SemVer(1, 0, 0), "draft body")
        val resolver = ReferenceResolver(repo)

        shouldThrow<DraftReferenceNotAllowedException> {
            resolver.resolveExtendsChain(
                "prompt:support/faq",
                ExtendsRef(TemplateKey("templates/base")),
                CompositionMode.STANDARD,
            )
        }
    }

    @Test
    fun `COMPILE_ONLYモードではDraft版を解決できる`() {
        val repo = FakeTemplateRepository()
        repo.addDraft(TemplateKey("templates/base"), SemVer(1, 0, 0), "draft body")
        val resolver = ReferenceResolver(repo)

        val result =
            resolver.resolveExtendsChain(
                "prompt:support/faq",
                ExtendsRef(TemplateKey("templates/base")),
                CompositionMode.COMPILE_ONLY,
            )

        result.single().status shouldBe PublicationState.Draft
    }

    @Test
    fun `Archived版は範囲に一致してもモードに関わらず解決対象にならない`() {
        val repo = FakeTemplateRepository()
        repo.addArchived(TemplateKey("templates/base"), SemVer(1, 0, 0), "archived body")
        val resolver = ReferenceResolver(repo)

        shouldThrow<TemplateReferenceNotFoundException> {
            resolver.resolveExtendsChain(
                "prompt:support/faq",
                ExtendsRef(TemplateKey("templates/base")),
                CompositionMode.COMPILE_ONLY,
            )
        }
    }

    @Test
    fun `同一リポジトリ状態から同じ入力を2回解決すると構造的に等しい結果になる 決定性`() {
        val repo = FakeTemplateRepository()
        repo.addPublished(TemplateKey("templates/base"), SemVer(1, 0, 0), "body")
        repo.addPublished(TemplateKey("templates/base"), SemVer(2, 0, 0), "body v2")
        val resolver = ReferenceResolver(repo)
        val extendsRef = ExtendsRef(TemplateKey("templates/base"), VersionRange.CaretMajor(2))

        val first = resolver.resolveExtendsChain("prompt:support/faq", extendsRef, CompositionMode.STANDARD)
        val second = resolver.resolveExtendsChain("prompt:support/faq", extendsRef, CompositionMode.STANDARD)

        first shouldBe second
    }

    private class FakeTemplateRepository : TemplateRepository {
        private val templates = mutableMapOf<TemplateKey, Template>()
        private val context =
            EventContext(actor = "tester", traceId = "trace-1", occurredAt = Instant.parse("2026-01-01T00:00:00Z"))

        fun addPublished(
            key: TemplateKey,
            semVer: SemVer,
            body: String,
            extends: ExtendsRef? = null,
        ) = addVersion(key, semVer, body, extends, publish = true)

        fun addDraft(
            key: TemplateKey,
            semVer: SemVer,
            body: String,
            extends: ExtendsRef? = null,
        ) = addVersion(key, semVer, body, extends, publish = false)

        fun addArchived(
            key: TemplateKey,
            semVer: SemVer,
            body: String,
            extends: ExtendsRef? = null,
        ) {
            addVersion(key, semVer, body, extends, publish = false)
            templates[key] = templates.getValue(key).archive(semVer, context).first
        }

        private fun addVersion(
            key: TemplateKey,
            semVer: SemVer,
            body: String,
            extends: ExtendsRef?,
            publish: Boolean,
        ) {
            val newVersion = NewTemplateVersion(semVer, TemplateContent(body), extends = extends)
            var template =
                templates[key]?.newVersion(newVersion, context)?.first
                    ?: Template.create(key, newVersion, context).first
            if (publish) template = template.publish(semVer, context).first
            templates[key] = template
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
}
