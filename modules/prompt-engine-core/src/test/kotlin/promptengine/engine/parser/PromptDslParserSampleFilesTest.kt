package promptengine.engine.parser

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.template.ast.ParseErrorKind
import promptengine.domain.template.ast.PromptDslParseException
import java.io.File

/**
 * `docs/dsl/samples/` 配下の正常系5本・異常系5本を全走査して検証する。
 * ファイルが1件も見つからない場合はテスト自体を失敗させる（ArchUnit規約6・
 * integrationTestと同じ方針。P3実装ガイド「テスト要件」）。
 */
class PromptDslParserSampleFilesTest {
    private val repoRoot = findRepoRoot()
    private val validDir = File(repoRoot, "docs/dsl/samples/valid")
    private val invalidDir = File(repoRoot, "docs/dsl/samples/invalid")

    @Test
    fun `valid配下のサンプルは1件以上存在し全て例外なくPromptDocumentへパースできる`() {
        val files = promptFiles(validDir)
        files.shouldNotBeEmpty()

        val parser = PromptDslParser()
        for (file in files) {
            withClue(file.name) {
                val document = parser.parse(file.readText())
                document.body.shouldNotBeEmpty()
            }
        }
    }

    @Test
    fun `invalid配下のサンプルは1件以上存在し全て期待したParseErrorの種別と位置を投げる`() {
        val files = promptFiles(invalidDir)
        files.shouldNotBeEmpty()
        files.map { it.name }.toSet() shouldBe expectedErrors.keys

        val parser = PromptDslParser()
        for (file in files) {
            val expected = expectedErrors.getValue(file.name)
            val exception = shouldThrow<PromptDslParseException> { parser.parse(file.readText()) }
            withClue(file.name) {
                exception.error.kind shouldBe expected.kind
                exception.error.line shouldBe expected.line
                exception.error.column shouldBe expected.column
            }
        }
    }

    private fun promptFiles(dir: File): List<File> =
        (
            dir.listFiles { f -> f.isFile && f.extension == "prompt" }
                ?: error("サンプルディレクトリが見つかりません: ${dir.absolutePath}")
        ).sortedBy { it.name }

    private data class ExpectedError(val kind: ParseErrorKind, val line: Int, val column: Int)

    private val expectedErrors =
        mapOf(
            // front matterの閉じ'---'が無いまま末尾に到達する（ファイル末尾の行を指す）。
            "01-unterminated-front-matter.prompt" to
                ExpectedError(ParseErrorKind.SYNTAX_ERROR, line = 6, column = 1),
            // `tags: [faq, customer` の閉じ']'が無いままflowシーケンスが続き、
            // 次の`name:`のコロンでYAMLスキャナがエラーになる。
            "02-malformed-yaml.prompt" to
                ExpectedError(ParseErrorKind.SYNTAX_ERROR, line = 6, column = 5),
            // {{#if}}を開いたまま{{/if}}が無く本文末尾に到達する。
            "03-unterminated-if.prompt" to
                ExpectedError(ParseErrorKind.SYNTAX_ERROR, line = 8, column = 1),
            // roleがsystem/user/assistantのいずれでもない。
            "04-invalid-block-role.prompt" to
                ExpectedError(ParseErrorKind.SYNTAX_ERROR, line = 7, column = 1),
            // {{#block}}+9段の{{#if}}ネスト。既定maxNestingDepth=8では8段目の{{#if}}（level8）で超過する。
            "05-nesting-too-deep.prompt" to
                ExpectedError(ParseErrorKind.NESTING_TOO_DEEP, line = 15, column = 1),
        )

    /**
     * settings.gradle.kts を上方探索してリポジトリルートを特定する
     * （[promptengine.bootstrap.ArchitectureTest] の findPluginSubprojectDirs と同じ方針）。
     */
    private fun findRepoRoot(): File =
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").exists() }
            ?: error("settings.gradle.kts が見つからずリポジトリルートを特定できません")
}
