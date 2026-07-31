package promptengine.plugin.formatter.json

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeType
import promptengine.domain.parsing.OutputFieldType
import promptengine.domain.parsing.OutputFormatter
import promptengine.domain.parsing.OutputSchema
import promptengine.domain.parsing.OutputSchemaField
import promptengine.domain.parsing.ParseFailedException
import promptengine.domain.parsing.ParsedOutput
import promptengine.domain.render.OutputFormat

/**
 * `OutputFormat.JSON`の[OutputFormatter]既定実装（実装ガイド§6.8）。
 *
 * [parse]はコードフェンス除去→JSON構文解析→[OutputSchema]検証（トップレベルの必須フィールド・
 * 型のみ、[OutputSchema]のKDoc参照）→[ParsedOutput]構築の順で行う。JSON構文エラー時、
 * Jacksonの例外メッセージ（入力の一部を含みうる）をそのまま転記せず、固定文字列
 * `"invalid JSON syntax"`を使う（ADR-0014決定9、秘密情報の非露出）。
 */
class JsonOutputFormatter : OutputFormatter {
    private val mapper = ObjectMapper()

    override fun format(): OutputFormat = OutputFormat.JSON

    override fun instruction(schema: OutputSchema?): String =
        buildString {
            append("必ずJSON形式のみで出力してください。前後に説明文やコードフェンスを含めないこと。")
            if (schema != null && schema.fields.isNotEmpty()) {
                append(" スキーマ(id=${schema.id}): ")
                append(
                    schema.fields.joinToString(separator = ", ") { field ->
                        val requiredSuffix = if (field.required) ", required" else ""
                        "${field.name}(${field.type}$requiredSuffix)"
                    },
                )
            }
        }

    override fun parse(
        raw: String,
        schema: OutputSchema?,
    ): ParsedOutput {
        val tree = parseTree(stripCodeFence(raw))
        if (!tree.isObject) {
            throw ParseFailedException(OutputFormat.JSON, "top-level JSON value must be an object")
        }

        schema?.fields.orEmpty().forEach { field -> validateField(tree, field) }

        val fields = mutableMapOf<String, Any?>()
        tree.fields().forEach { (key, value) -> fields[key] = toKotlinValue(value) }

        return ParsedOutput(OutputFormat.JSON, fields, raw)
    }

    private fun parseTree(json: String): JsonNode =
        try {
            mapper.readTree(json)
        } catch (e: JsonProcessingException) {
            // Jacksonの例外メッセージは入力の一部を含みうるため、reason/messageには転記せず
            // causeとしてのみ連鎖する（ADR-0014決定9）。
            throw ParseFailedException(OutputFormat.JSON, "invalid JSON syntax", cause = e)
        }

    private fun validateField(
        tree: JsonNode,
        field: OutputSchemaField,
    ) {
        val value = tree.get(field.name)
        val isMissing = value == null || value.isNull
        if (field.required && isMissing) {
            throw ParseFailedException(OutputFormat.JSON, "missing required field: ${field.name}")
        }
        if (!isMissing && !matchesType(value, field.type)) {
            throw ParseFailedException(
                OutputFormat.JSON,
                "field '${field.name}' expected ${field.type} but was ${value.nodeType}",
            )
        }
    }

    private fun matchesType(
        value: JsonNode,
        type: OutputFieldType,
    ): Boolean =
        when (type) {
            OutputFieldType.STRING -> value.nodeType == JsonNodeType.STRING
            OutputFieldType.NUMBER -> value.nodeType == JsonNodeType.NUMBER
            OutputFieldType.BOOLEAN -> value.nodeType == JsonNodeType.BOOLEAN
            OutputFieldType.ARRAY -> value.nodeType == JsonNodeType.ARRAY
            OutputFieldType.OBJECT -> value.nodeType == JsonNodeType.OBJECT
        }

    private fun toKotlinValue(node: JsonNode): Any? =
        when (node.nodeType) {
            JsonNodeType.STRING -> node.asText()
            JsonNodeType.NUMBER -> if (node.isIntegralNumber) node.asLong() else node.asDouble()
            JsonNodeType.BOOLEAN -> node.asBoolean()
            JsonNodeType.ARRAY -> node.map { toKotlinValue(it) }
            JsonNodeType.OBJECT -> node.fields().asSequence().associate { (key, value) -> key to toKotlinValue(value) }
            else -> null
        }

    private fun stripCodeFence(raw: String): String {
        val trimmed = raw.trim()
        val match = CODE_FENCE_REGEX.find(trimmed) ?: return trimmed
        return match.groupValues[1].trim()
    }

    companion object {
        private val CODE_FENCE_REGEX = Regex("^```(?:json)?\\s*\\n?([\\s\\S]*?)\\n?```$", RegexOption.IGNORE_CASE)
    }
}
