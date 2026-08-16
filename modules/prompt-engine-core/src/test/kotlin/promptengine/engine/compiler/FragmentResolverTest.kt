package promptengine.engine.compiler

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.composition.CircularDependencyException
import promptengine.domain.composition.CompositionDepthExceededException
import promptengine.domain.composition.CompositionMode
import promptengine.domain.composition.DraftReferenceNotAllowedException
import promptengine.domain.composition.FragmentReferenceNotFoundException
import promptengine.domain.composition.IncludeRequiredVariableUnresolvedException
import promptengine.domain.composition.ResolvedDependency
import promptengine.domain.event.EventContext
import promptengine.domain.fragment.Fragment
import promptengine.domain.fragment.FragmentContent
import promptengine.domain.fragment.FragmentDomainEvent
import promptengine.domain.fragment.FragmentKey
import promptengine.domain.fragment.FragmentRepository
import promptengine.domain.fragment.NewFragmentVersion
import promptengine.domain.shared.PublicationState
import promptengine.domain.shared.SemVer
import promptengine.domain.shared.VersionRange
import promptengine.domain.template.ast.ExprNode
import promptengine.domain.template.ast.Expression
import promptengine.domain.template.ast.IncludeNode
import promptengine.domain.template.ast.PropertyRef
import promptengine.domain.template.ast.StringLiteral
import promptengine.domain.template.ast.TextNode
import promptengine.domain.variable.VariableDefinition
import promptengine.domain.variable.VariableType
import java.time.Instant

/** [FragmentResolver]のテスト（設計書§15.4/§15.5、ADR-0009決定4〜7、ADR-0010決定1・2・4・7）。 */
class FragmentResolverTest {
    private fun expr(name: String): Expression = Expression(PropertyRef(listOf(name)))

    private fun literal(value: String): Expression = Expression(StringLiteral(value))

    @Test
    fun `Include が無い本文はそのまま返る`() {
        val repo = FakeFragmentRepository()
        val resolver = FragmentResolver(repo)
        val body = listOf(TextNode("hello"))

        val result = resolver.resolve(body, emptyList(), emptySet(), CompositionMode.STANDARD)

        result.body shouldBe body
        result.dependencies shouldBe emptyList()
    }

    @Test
    fun `明示束縛でFragment内の変数参照を置換する`() {
        val repo = FakeFragmentRepository()
        repo.addPublished(
            FragmentKey("fragments/greeting"),
            SemVer(1, 0, 0),
            "{{ name }}",
            variables = listOf(VariableDefinition("name", VariableType.STRING, required = true)),
        )
        val resolver = FragmentResolver(repo)
        val body = listOf(IncludeNode("fragments/greeting", null, mapOf("name" to literal("Alice"))))

        val result = resolver.resolve(body, emptyList(), emptySet(), CompositionMode.STANDARD)

        result.body shouldBe listOf(ExprNode(literal("Alice")))
        result.dependencies shouldBe
            listOf(
                ResolvedDependency.FragmentDependency(
                    FragmentKey("fragments/greeting"),
                    VersionRange.Latest,
                    SemVer(1, 0, 0),
                    PublicationState.Published,
                    repo.contentHashOf(FragmentKey("fragments/greeting"), SemVer(1, 0, 0)),
                ),
            )
    }

    @Test
    fun `未指定の変数は呼出側スコープを透過継承しそのまま残る`() {
        val repo = FakeFragmentRepository()
        repo.addPublished(
            FragmentKey("fragments/greeting"),
            SemVer(1, 0, 0),
            "{{ name }}",
            variables = listOf(VariableDefinition("name", VariableType.STRING, required = true)),
        )
        val resolver = FragmentResolver(repo)
        val body = listOf(IncludeNode("fragments/greeting", null, emptyMap()))

        val result = resolver.resolve(body, emptyList(), setOf("name"), CompositionMode.STANDARD)

        result.body shouldBe listOf(ExprNode(expr("name")))
    }

    @Test
    fun `明示束縛は呼出側スコープの同名変数より優先される`() {
        val repo = FakeFragmentRepository()
        repo.addPublished(
            FragmentKey("fragments/greeting"),
            SemVer(1, 0, 0),
            "{{ name }}",
            variables = listOf(VariableDefinition("name", VariableType.STRING, required = true)),
        )
        val resolver = FragmentResolver(repo)
        val body = listOf(IncludeNode("fragments/greeting", null, mapOf("name" to literal("Bob"))))

        val result = resolver.resolve(body, emptyList(), setOf("name"), CompositionMode.STANDARD)

        result.body shouldBe listOf(ExprNode(literal("Bob")))
    }

    @Test
    fun `必須変数が束縛にも呼出側スコープにも無い場合はIncludeRequiredVariableUnresolvedExceptionを投げる`() {
        val repo = FakeFragmentRepository()
        repo.addPublished(
            FragmentKey("fragments/greeting"),
            SemVer(1, 0, 0),
            "{{ name }}",
            variables = listOf(VariableDefinition("name", VariableType.STRING, required = true)),
        )
        val resolver = FragmentResolver(repo)
        val body = listOf(IncludeNode("fragments/greeting", null, emptyMap()))

        val exception =
            shouldThrow<IncludeRequiredVariableUnresolvedException> {
                resolver.resolve(body, emptyList(), emptySet(), CompositionMode.STANDARD)
            }
        exception.fragmentKey shouldBe FragmentKey("fragments/greeting")
        exception.variableName shouldBe "name"
    }

    @Test
    fun `ネストしたIncludeでは外側のFragmentが宣言した変数も呼出側スコープに含まれる`() {
        val repo = FakeFragmentRepository()
        repo.addPublished(
            FragmentKey("fragments/inner"),
            SemVer(1, 0, 0),
            "{{ tone }}",
            variables = listOf(VariableDefinition("tone", VariableType.STRING, required = true)),
        )
        repo.addPublished(
            FragmentKey("fragments/outer"),
            SemVer(1, 0, 0),
            "{{> fragments/inner }}",
            variables = listOf(VariableDefinition("tone", VariableType.STRING, required = true)),
        )
        val resolver = FragmentResolver(repo)
        val body = listOf(IncludeNode("fragments/outer", null, mapOf("tone" to literal("polite"))))

        val result = resolver.resolve(body, emptyList(), emptySet(), CompositionMode.STANDARD)

        result.body shouldBe listOf(ExprNode(literal("polite")))
    }

    @Test
    fun `同一Fragmentへの多重取込は内部解決を1回に正規化しつつ呼出箇所ごとに別々の束縛を適用する`() {
        val repo = FakeFragmentRepository()
        repo.addPublished(
            FragmentKey("fragments/greeting"),
            SemVer(1, 0, 0),
            "{{ name }}",
            variables = listOf(VariableDefinition("name", VariableType.STRING, required = true)),
        )
        val resolver = FragmentResolver(repo)
        val body =
            listOf(
                IncludeNode("fragments/greeting", null, mapOf("name" to literal("Alice"))),
                IncludeNode("fragments/greeting", null, mapOf("name" to literal("Bob"))),
            )

        val result = resolver.resolve(body, emptyList(), emptySet(), CompositionMode.STANDARD)

        result.body shouldBe listOf(ExprNode(literal("Alice")), ExprNode(literal("Bob")))
        result.dependencies.size shouldBe 1
    }

    @Test
    fun `alias経由の参照はimports宣言の範囲で解決される`() {
        val repo = FakeFragmentRepository()
        repo.addPublished(FragmentKey("fragments/safety-policy"), SemVer(1, 9, 0), "old")
        repo.addPublished(FragmentKey("fragments/safety-policy"), SemVer(2, 3, 0), "v2.3")
        val resolver = FragmentResolver(repo)
        val imports =
            listOf(ImportDeclaration("safety", FragmentKey("fragments/safety-policy"), VersionRange.CaretMajor(2)))
        val body = listOf(IncludeNode("safety", null, emptyMap()))

        val result = resolver.resolve(body, imports, emptySet(), CompositionMode.STANDARD)

        result.body shouldBe listOf(TextNode("v2.3"))
    }

    @Test
    fun `自己Includeの循環はCircularDependencyExceptionを投げる`() {
        val repo = FakeFragmentRepository()
        repo.addPublished(FragmentKey("fragments/self"), SemVer(1, 0, 0), "{{> fragments/self }}")
        val resolver = FragmentResolver(repo)
        val body = listOf(IncludeNode("fragments/self", null, emptyMap()))

        shouldThrow<CircularDependencyException> {
            resolver.resolve(body, emptyList(), emptySet(), CompositionMode.STANDARD)
        }
    }

    @Test
    fun `extendsチェーンで既に消費した深さを引き継いで判定する`() {
        val repo = FakeFragmentRepository()
        repo.addPublished(FragmentKey("fragments/leaf"), SemVer(1, 0, 0), "leaf")
        val resolver = FragmentResolver(repo, maxDepth = 1)
        val body = listOf(IncludeNode("fragments/leaf", null, emptyMap()))
        val chainState = FragmentResolver.ChainState(ancestorPath = listOf("templates/base"))

        shouldThrow<CompositionDepthExceededException> {
            resolver.resolve(body, emptyList(), emptySet(), CompositionMode.STANDARD, chainState)
        }
    }

    @Test
    fun `深さ上限を超えるとCompositionDepthExceededExceptionを投げる`() {
        val repo = FakeFragmentRepository()
        (1..6).forEach { i ->
            val bodyText = if (i < 6) "{{> fragments/f${i + 1} }}" else "leaf"
            repo.addPublished(FragmentKey("fragments/f$i"), SemVer(1, 0, 0), bodyText)
        }
        val resolver = FragmentResolver(repo)
        val body = listOf(IncludeNode("fragments/f1", null, emptyMap()))

        shouldThrow<CompositionDepthExceededException> {
            resolver.resolve(body, emptyList(), emptySet(), CompositionMode.STANDARD)
        }
    }

    @Test
    fun `STANDARDモードではDraft版しかない参照はDraftReferenceNotAllowedExceptionを投げる`() {
        val repo = FakeFragmentRepository()
        repo.addDraft(FragmentKey("fragments/greeting"), SemVer(1, 0, 0), "hi")
        val resolver = FragmentResolver(repo)
        val body = listOf(IncludeNode("fragments/greeting", null, emptyMap()))

        shouldThrow<DraftReferenceNotAllowedException> {
            resolver.resolve(body, emptyList(), emptySet(), CompositionMode.STANDARD)
        }
    }

    @Test
    fun `COMPILE_ONLYモードではDraft版を解決できる`() {
        val repo = FakeFragmentRepository()
        repo.addDraft(FragmentKey("fragments/greeting"), SemVer(1, 0, 0), "hi")
        val resolver = FragmentResolver(repo)
        val body = listOf(IncludeNode("fragments/greeting", null, emptyMap()))

        val result = resolver.resolve(body, emptyList(), emptySet(), CompositionMode.COMPILE_ONLY)

        result.body shouldBe listOf(TextNode("hi"))
    }

    @Test
    fun `参照先Fragmentが存在しない場合はFragmentReferenceNotFoundExceptionを投げる`() {
        val resolver = FragmentResolver(FakeFragmentRepository())
        val body = listOf(IncludeNode("fragments/missing", null, emptyMap()))

        shouldThrow<FragmentReferenceNotFoundException> {
            resolver.resolve(body, emptyList(), emptySet(), CompositionMode.STANDARD)
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
            variables: List<VariableDefinition> = emptyList(),
        ) = addVersion(key, semVer, bodyText, variables, publish = true)

        fun addDraft(
            key: FragmentKey,
            semVer: SemVer,
            bodyText: String,
            variables: List<VariableDefinition> = emptyList(),
        ) = addVersion(key, semVer, bodyText, variables, publish = false)

        fun contentHashOf(
            key: FragmentKey,
            semVer: SemVer,
        ): String = fragments.getValue(key).versions.single { it.semVer == semVer }.content.contentHash

        private fun addVersion(
            key: FragmentKey,
            semVer: SemVer,
            bodyText: String,
            variables: List<VariableDefinition>,
            publish: Boolean,
        ) {
            val source =
                """
                ---
                pe: "1"
                kind: fragment
                key: ${key.value}
                ---
                $bodyText
                """.trimIndent()
            val newVersion = NewFragmentVersion(semVer, FragmentContent(source), variables)
            var fragment =
                fragments[key]?.newVersion(newVersion, context)?.first
                    ?: Fragment.create(key, newVersion, context).first
            if (publish) fragment = fragment.publish(semVer, context).first
            fragments[key] = fragment
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
}
