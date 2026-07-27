package promptengine.engine.parser

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.MarkedYAMLException
import promptengine.domain.template.ast.ParseError
import promptengine.domain.template.ast.ParseErrorKind
import promptengine.domain.template.ast.PromptDocument
import promptengine.domain.template.ast.PromptDslParseException
import promptengine.domain.template.ast.PromptFrontMatter
import promptengine.engine.parser.internal.BodyParser

/**
 * パーサの安全弁（設計書P3実装ガイド「入力サイズ上限とネスト深さ上限をパーサ側にも
 * 設ける（設定値で外出し）」）。`maxNestingDepth` はDSL本文内の `{{#if}}/{{#each}}/
 * {{#block}}` の入れ子数であり、Composition解決（extends/import/include、§15.5）の
 * 深さ上限5とは別概念（3aのスコープ外）。
 */
data class PromptDslParserConfig(
    val maxInputSizeChars: Int = 1_000_000,
    val maxNestingDepth: Int = 8,
)

private const val FRONT_MATTER_DELIMITER = "---"

/**
 * Prompt DSL（設計書§15.1、拡張子 `.prompt`）のParser。
 * Front Matter（YAML）と本文を分離し、本文を再帰下降で構文解析して[PromptDocument]を返す。
 * 構文エラーは最初の1件で[PromptDslParseException]を投げて停止する。
 *
 * Front Matterの解析は[SafeConstructor]（任意クラスのインスタンス化を許さない）で行う。
 * 意味解釈（`kind`/`variables`/`context`等のドメイン型へのマッピング）はTemplate/
 * Fragment Aggregate（3b）の責務であり、本Parserは構文解析のみを担う。
 */
class PromptDslParser(private val config: PromptDslParserConfig = PromptDslParserConfig()) {
    private val yaml = Yaml(SafeConstructor(LoaderOptions()))

    fun parse(source: String): PromptDocument {
        checkInputSize(source)
        val sourceLines = source.lines()
        val split = splitFrontMatter(sourceLines)
        val frontMatter = parseFrontMatter(split.frontMatterYaml, split.frontMatterStartLine)
        val body = BodyParser(split.bodyText, split.bodyStartLine, sourceLines, config.maxNestingDepth).parse()
        return PromptDocument(frontMatter, body)
    }

    private fun checkInputSize(source: String) {
        if (source.length > config.maxInputSizeChars) {
            val firstLine = source.substringBefore('\n')
            throw PromptDslParseException(
                ParseError(
                    kind = ParseErrorKind.INPUT_TOO_LARGE,
                    line = 1,
                    column = 1,
                    lineText = firstLine,
                    message = "input size ${source.length} exceeds maximum of ${config.maxInputSizeChars} characters",
                ),
            )
        }
    }

    private data class FrontMatterSplit(
        val frontMatterYaml: String,
        val frontMatterStartLine: Int,
        val bodyText: String,
        val bodyStartLine: Int,
    )

    private fun splitFrontMatter(sourceLines: List<String>): FrontMatterSplit {
        // sourceLinesは`source.lines()`の結果であり、Kotlinの仕様上どんな入力
        // （空文字列を含む）に対しても要素数1以上のリストを返すため、
        // `sourceLines.isEmpty()`は到達不能（監査時点で削除済み）。
        if (sourceLines[0].trim() != FRONT_MATTER_DELIMITER) {
            fail(1, 1, sourceLines, "prompt file must start with a '---' front matter delimiter")
        }
        val closingIndex = (1 until sourceLines.size).firstOrNull { sourceLines[it].trim() == FRONT_MATTER_DELIMITER }
        if (closingIndex == null) {
            fail(
                sourceLines.size,
                1,
                sourceLines,
                "unterminated front matter: missing closing '---'",
            )
        }
        val frontMatterYaml = sourceLines.subList(1, closingIndex).joinToString("\n")
        val bodyText = sourceLines.subList(closingIndex + 1, sourceLines.size).joinToString("\n")
        return FrontMatterSplit(
            frontMatterYaml = frontMatterYaml,
            frontMatterStartLine = 2,
            bodyText = bodyText,
            bodyStartLine = closingIndex + 2,
        )
    }

    private fun parseFrontMatter(
        yamlText: String,
        startLine: Int,
    ): PromptFrontMatter {
        val loaded =
            try {
                yaml.load<Any?>(yamlText)
            } catch (e: MarkedYAMLException) {
                // MarkedYAMLException#getProblemMark() と #getProblem() はsnakeyaml側の
                // 公開APIとしてnullableに宣言されている（呼出側でnullを返す入力を確実に
                // 再現・制御できない）。テスト資産で確実にnullを再現できるYAML入力は
                // 監査時点で特定できなかったため、防御的なフォールバックとして残す
                // （監査: prompt-engine-core分岐カバレッジ）。
                val mark = e.problemMark
                val line = (mark?.line ?: 0) + startLine
                val column = (mark?.column ?: 0) + 1
                throw PromptDslParseException(
                    ParseError(
                        kind = ParseErrorKind.SYNTAX_ERROR,
                        line = line,
                        column = column,
                        lineText = yamlText.lines().getOrElse((mark?.line ?: 0)) { "" },
                        message = "front matter YAML syntax error: ${e.problem ?: e.message}",
                    ),
                    cause = e,
                )
            }
        if (loaded == null) return PromptFrontMatter(emptyMap())
        if (loaded !is Map<*, *>) {
            // loaded != null かつ Map でもないので、yaml.load はここで空文字列以外の
            // 何らかのYAML値をパースしている。空文字列は既に loaded == null で
            // return済みのため、yamlText.lines() は要素数1以上（Kotlinの仕様上、
            // どんな文字列に対しても.lines()は空リストを返さない）。firstOrNull()の
            // null分岐は到達不能なので.first()に簡略化済み（監査）。
            throw PromptDslParseException(
                ParseError(
                    kind = ParseErrorKind.SYNTAX_ERROR,
                    line = startLine,
                    column = 1,
                    lineText = yamlText.lines().first(),
                    message = "front matter must be a YAML mapping",
                ),
            )
        }
        @Suppress("UNCHECKED_CAST")
        return PromptFrontMatter((loaded as Map<String, Any?>))
    }

    /**
     * 呼出元（[splitFrontMatter]）の2箇所は共に`line`が常に`sourceLines`の
     * 有効範囲内（1件目、または末尾行）になるよう構築しているため、
     * `sourceLines[line - 1]`は範囲外にならない（監査時点で確認済み、
     * `getOrElse`のフォールバックは削除済み）。
     */
    private fun fail(
        line: Int,
        column: Int,
        sourceLines: List<String>,
        message: String,
    ): Nothing {
        throw PromptDslParseException(
            ParseError(
                kind = ParseErrorKind.SYNTAX_ERROR,
                line = line,
                column = column,
                lineText = sourceLines[line - 1],
                message = message,
            ),
        )
    }
}
