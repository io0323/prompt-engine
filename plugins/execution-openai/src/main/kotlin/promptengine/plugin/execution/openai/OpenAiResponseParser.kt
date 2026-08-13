package promptengine.plugin.execution.openai

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import promptengine.domain.execution.ExecutionErrorType
import promptengine.domain.execution.ExecutionFailedException
import promptengine.domain.execution.RawResponse
import promptengine.domain.execution.Usage
import promptengine.domain.shared.LatencyMs
import promptengine.domain.shared.SensitiveValue
import promptengine.domain.shared.TokenCount
import java.net.http.HttpResponse

/**
 * OpenAI Chat Completions APIのHTTP応答→[RawResponse]変換（M2-1a、ADR-0029決定5）。
 *
 * [OpenAiExecutionAdapter]から分離した専用object（HTTP送受信の責務と応答解釈の責務を分ける、
 * detektの関数数閾値を超えないための整理も兼ねる）。usage/content欠落時に0埋め・空文字で
 * 済ませずエラーとして扱う理由・副作用抑制の方針はADR-0029決定5参照。
 */
internal object OpenAiResponseParser {
    fun parse(
        objectMapper: ObjectMapper,
        httpResponse: HttpResponse<String>,
        latency: LatencyMs,
    ): RawResponse {
        requireOkStatus(objectMapper, httpResponse)
        val tree = parseJsonTree(objectMapper, httpResponse.body())
        return RawResponse(
            content = SensitiveValue.of(extractContent(tree)),
            usage = extractUsage(tree),
            latency = latency,
        )
    }

    private fun requireOkStatus(
        objectMapper: ObjectMapper,
        httpResponse: HttpResponse<String>,
    ) {
        val status = httpResponse.statusCode()
        if (status == HTTP_OK) return
        val errorType = classifyStatus(status)
        val cause =
            if (errorType == ExecutionErrorType.CLIENT_ERROR) {
                contextLengthExceededCause(objectMapper, httpResponse.body())
            } else {
                null
            }
        throw ExecutionFailedException(errorType, retryCount = 0, cause = cause)
    }

    /**
     * `error.code == "context_length_exceeded"`（OpenAIの構造化エラーコード）をベストエフォートで
     * 検出する（M2-1c、ADR-0030決定1）。ボディがJSONとして解釈できない・想定した形でない場合は
     * `null`を返し、呼出元は通常の`CLIENT_ERROR`（causeなし）として扱う——この検出はあくまで
     * 運用診断の補助であり、失敗時にステータスコード自体の分類を妨げてはならない。
     */
    @Suppress("SwallowedException")
    private fun contextLengthExceededCause(
        objectMapper: ObjectMapper,
        body: String,
    ): Throwable? {
        // ボディがJSONとして解釈できない場合、この検出はあきらめて通常のCLIENT_ERROR
        // （causeなし）に倒す。ステータス自体は既に確定しているため、この段階の例外を
        // 呼出元へ伝える必要は無い（意図的なswallow）。
        val tree =
            try {
                objectMapper.readTree(body)
            } catch (e: JsonProcessingException) {
                return null
            }
        val code = tree.path("error").path("code")
        return if (code.isTextual && code.asText() == OPENAI_CONTEXT_LENGTH_EXCEEDED_CODE) {
            OpenAiContextLengthExceededException()
        } else {
            null
        }
    }

    private fun parseJsonTree(
        objectMapper: ObjectMapper,
        body: String,
    ): JsonNode =
        try {
            objectMapper.readTree(body)
        } catch (e: JsonProcessingException) {
            throw ExecutionFailedException(
                ExecutionErrorType.UNKNOWN,
                retryCount = 0,
                cause = OpenAiMalformedResponseException(e),
            )
        }

    private fun extractContent(tree: JsonNode): String {
        val contentNode = tree.path("choices").firstOrNull()?.path("message")?.path("content")
        if (contentNode == null || !contentNode.isTextual) {
            throw ExecutionFailedException(
                ExecutionErrorType.UNKNOWN,
                retryCount = 0,
                cause = OpenAiMissingContentException(),
            )
        }
        return contentNode.asText()
    }

    private fun extractUsage(tree: JsonNode): Usage {
        val usageNode = tree.path("usage")
        val promptTokens = usageNode.path("prompt_tokens")
        val completionTokens = usageNode.path("completion_tokens")
        if (isUsageInvalid(usageNode, promptTokens, completionTokens)) {
            throw ExecutionFailedException(
                ExecutionErrorType.UNKNOWN,
                retryCount = 0,
                cause = OpenAiUsageMissingException(),
            )
        }
        return Usage(TokenCount(promptTokens.asInt()), TokenCount(completionTokens.asInt()))
    }

    private fun isUsageInvalid(
        usageNode: JsonNode,
        promptTokens: JsonNode,
        completionTokens: JsonNode,
    ): Boolean =
        usageNode.isMissingNode ||
            usageNode.isNull ||
            !isValidTokenCount(promptTokens) ||
            !isValidTokenCount(completionTokens)

    /**
     * [TokenCount]（`require(value >= 0)`のみでIntの範囲チェックは持たない）へ安全に渡せる値か
     * どうかを判定する（CodeRabbitレビュー指摘）。`isIntegralNumber`だけでは負値・Int範囲超過
     * （`asInt()`が黙って切り詰める）を弾けず、[TokenCount]の`require`が投げる
     * [IllegalArgumentException]が[promptengine.domain.execution.ExecutionFailedException]で
     * 包まれず[OpenAiExecutionAdapter.execute]の契約から漏れてしまう。`canConvertToInt()`で
     * Int範囲内であることを、非負チェックで符号を確認する。
     */
    private fun isValidTokenCount(node: JsonNode): Boolean =
        node.isIntegralNumber && node.canConvertToInt() && node.asInt() >= 0

    /**
     * HTTPステータスコード→[ExecutionErrorType]（ADR-0014のリトライ可否表と完全一致させる）。
     * `429`は5xx/4xxの汎用判定より先に判定する必要がある（`429`は4xx範囲に含まれるがリトライ可否が
     * 他の4xxと異なるため）。上限を持つ閉区間（`500..599`等）ではなく`>=`で判定する。600以上の
     * 不正なステータスコードもサーバ側の異常として扱うのが実用上妥当であり、上限判定のための
     * 追加の分岐（テストで維持すべき分岐）を増やさないため。
     */
    private fun classifyStatus(status: Int): ExecutionErrorType =
        when {
            status == HTTP_TOO_MANY_REQUESTS -> ExecutionErrorType.RATE_LIMITED
            status >= HTTP_SERVER_ERROR_THRESHOLD -> ExecutionErrorType.SERVER_ERROR
            status >= HTTP_CLIENT_ERROR_THRESHOLD -> ExecutionErrorType.CLIENT_ERROR
            else -> ExecutionErrorType.UNKNOWN
        }

    private const val HTTP_OK = 200
    private const val HTTP_TOO_MANY_REQUESTS = 429
    private const val HTTP_CLIENT_ERROR_THRESHOLD = 400
    private const val HTTP_SERVER_ERROR_THRESHOLD = 500
    private const val OPENAI_CONTEXT_LENGTH_EXCEEDED_CODE = "context_length_exceeded"
}
