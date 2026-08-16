package promptengine.tests.regression

import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import promptengine.domain.composition.CompositionMode
import promptengine.domain.context.ContextBindingSet
import promptengine.domain.event.EventContext
import promptengine.domain.fragment.Fragment
import promptengine.domain.fragment.FragmentDomainEvent
import promptengine.domain.fragment.FragmentKey
import promptengine.domain.fragment.FragmentRepository
import promptengine.domain.parsing.OutputFormatter
import promptengine.domain.parsing.OutputSchema
import promptengine.domain.parsing.ParsedOutput
import promptengine.domain.prompt.NewPromptVersion
import promptengine.domain.prompt.Prompt
import promptengine.domain.prompt.PromptContent
import promptengine.domain.prompt.PromptKey
import promptengine.domain.render.OutputFormat
import promptengine.domain.shared.SemVer
import promptengine.domain.shared.TokenCount
import promptengine.domain.template.Template
import promptengine.domain.template.TemplateDomainEvent
import promptengine.domain.template.TemplateKey
import promptengine.domain.template.TemplateRepository
import promptengine.domain.tokenizer.TokenizerPlugin
import promptengine.domain.variable.BindingSet
import promptengine.engine.compiler.CompositionServiceImpl
import promptengine.engine.render.DefaultTemplateEngine
import promptengine.engine.render.RenderEngineImpl
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** [OutputFormatter.instruction]が常に空文字を返すテスト用スタブ（[RenderEngineImplTest]と同じ形）。 */
private object BlankOutputFormatter : OutputFormatter {
    override fun format(): OutputFormat = OutputFormat.TEXT

    override fun instruction(schema: OutputSchema?): String = ""

    override fun parse(
        raw: String,
        schema: OutputSchema?,
    ): ParsedOutput = ParsedOutput(OutputFormat.TEXT, raw = raw)
}

private class EmptyTemplateRepository : TemplateRepository {
    override fun findByKey(key: TemplateKey): Template? = null

    override fun save(
        template: Template,
        events: List<TemplateDomainEvent>,
    ): Template = error("not used by Golden回帰テスト固定fixture")
}

private class EmptyFragmentRepository : FragmentRepository {
    override fun findByKey(key: FragmentKey): Fragment? = null

    override fun save(
        fragment: Fragment,
        events: List<FragmentDomainEvent>,
    ): Fragment = error("not used by Golden回帰テスト固定fixture")
}

/**
 * Golden Prompt回帰テスト（P11、実装ガイド§6.12）。
 *
 * `fixtures/valid`配下の`.prompt`ファイルをParse（[Prompt.create]内部）→
 * Compile（[CompositionServiceImpl]）→Render（[RenderEngineImpl]）まで通し、決定的な
 * `renderHash`（FR-011）を`golden`配下の`<fixture名>.hash`と比較する。DBもTestcontainersも使わない
 * （render pipelineは全てin-memoryで完結する。[promptengine.engine.render.RenderEngineImplTest]と
 * 同じ構成）。
 *
 * **0件ガード**: fixtureディレクトリが空、またはfixtureとgoldenの対応が壊れている状態で
 * 「テスト対象が無いので成功」にならないよう、[fixtures]が空なら明示的に失敗する。
 *
 * **golden更新手順**（意図的な変更でrenderHashが変わった場合）:
 * 1. `RENDER_REGRESSION_UPDATE_GOLDEN=1 ./gradlew :tests:prompt-regression:test` を実行する
 *    （`golden`配下の既存`.hash`ファイルを実測値で上書きする）。
 * 2. `git diff tests/prompt-regression/golden/` で差分がDSL/render pipelineへの意図した変更と
 *    一致することを確認してからコミットする。
 */
class GoldenPromptRegressionTest {
    @TestFactory
    fun `fixtureのrenderHashがgoldenと一致する`(): List<DynamicTest> {
        val fixtures = fixtureFiles()
        fixtures.shouldNotBeEmpty()

        val fixtureNames = fixtures.map { it.nameWithoutExtension }.toSet()
        val orphanedGoldenNames =
            Files.list(goldenDir()).use { stream ->
                stream.filter { it.extension == "hash" }.map { it.nameWithoutExtension }.toList().toSet()
            } - fixtureNames
        check(orphanedGoldenNames.isEmpty()) {
            "fixtureが無いgolden fileが残っている（削除漏れ）: $orphanedGoldenNames"
        }

        return fixtures.map { fixture ->
            DynamicTest.dynamicTest(fixture.nameWithoutExtension) {
                val actualHash = renderHashOf(fixture)
                val goldenFile = goldenDir().resolve("${fixture.nameWithoutExtension}.hash")

                if (System.getenv("RENDER_REGRESSION_UPDATE_GOLDEN") == "1") {
                    goldenFile.writeText(actualHash)
                }

                check(goldenFile.exists()) {
                    "golden file missing: $goldenFile" +
                        "（RENDER_REGRESSION_UPDATE_GOLDEN=1で生成してください。クラスKDoc参照）"
                }
                actualHash shouldBe goldenFile.readText().trim()
            }
        }
    }

    private fun renderHashOf(fixture: Path): String {
        val source = fixture.readText()
        val promptKey = PromptKey(FIXTURE_PROMPT_KEY_PREFIX + fixture.nameWithoutExtension)
        val newVersion = NewPromptVersion(SemVer(1, 0, 0), PromptContent(source))
        val eventContext =
            EventContext(actor = "golden-regression-test", traceId = "fixture", occurredAt = Instant.EPOCH)
        val promptVersion = Prompt.create(promptKey, newVersion, eventContext).first.versions.first()

        val compositionService = CompositionServiceImpl(EmptyTemplateRepository(), EmptyFragmentRepository())
        val compiled = compositionService.compile(promptKey, promptVersion, CompositionMode.STANDARD)

        val tokenizer = TokenizerPlugin { text -> TokenCount(text.length) }
        val formatters = OutputFormat.entries.associateWith { BlankOutputFormatter }
        val renderEngine = RenderEngineImpl(DefaultTemplateEngine(), tokenizer, formatters)

        val bindings = FIXTURE_BINDINGS[fixture.nameWithoutExtension] ?: BindingSet(emptyMap())
        return renderEngine.render(compiled, bindings, ContextBindingSet.empty(), OutputFormat.TEXT).renderHash
    }

    private fun fixtureFiles(): List<Path> =
        Files.list(fixtureDir()).use { stream ->
            stream.filter { it.extension == "prompt" }.sorted(compareBy { it.name }).toList()
        }

    private fun fixtureDir(): Path = repoRelative("tests/prompt-regression/fixtures/valid")

    private fun goldenDir(): Path = repoRelative("tests/prompt-regression/golden")

    /** `settings.gradle.kts`を目印にリポジトリルートまで遡る（[promptengine.engine.parser.PromptDslParserSampleFilesTest]と同じ探索方式）。 */
    private fun repoRelative(relative: String): Path {
        var dir = Path.of("").toAbsolutePath()
        while (!dir.resolve("settings.gradle.kts").exists()) {
            dir = dir.parent ?: error("settings.gradle.ktsが見つからない（リポジトリ外で実行された）")
        }
        return dir.resolve(relative)
    }

    private companion object {
        const val FIXTURE_PROMPT_KEY_PREFIX = "regression/"

        val FIXTURE_BINDINGS: Map<String, BindingSet> =
            mapOf(
                "01-basic-blocks" to BindingSet(mapOf("userName" to "  taro  ")),
                "02-filters-and-macro" to
                    BindingSet(
                        mapOf(
                            "title" to "quarterly release notes for the platform",
                            "points" to listOf("performance improvements", "bug fixes", "new dashboard"),
                        ),
                    ),
                "03-conditional-loop" to
                    BindingSet(
                        mapOf(
                            "workflow" to
                                mapOf(
                                    "active" to true,
                                    "name" to "Q3 onboarding",
                                    "steps" to
                                        listOf(
                                            mapOf("done" to true, "label" to "アカウント作成"),
                                            mapOf("done" to false, "label" to "初回ログイン"),
                                        ),
                                ),
                        ),
                    ),
                "04-production-scale-support-agent" to
                    BindingSet(
                        mapOf(
                            "customerName" to "山田太郎",
                            "ticketSummary" to "  ダッシュボードのグラフが表示されない現象について。ブラウザはChrome最新版。  ",
                        ),
                    ),
            )
    }
}
