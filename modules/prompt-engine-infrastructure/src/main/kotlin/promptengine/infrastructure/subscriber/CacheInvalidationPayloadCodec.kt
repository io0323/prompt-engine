package promptengine.infrastructure.subscriber

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import promptengine.domain.dependency.DependencyKind
import promptengine.domain.dependency.DependencyRepository
import promptengine.domain.event.EventEnvelope
import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.SemVer

/** [CacheInvalidationSubscriber]が無効化すべき対象（ADR-0033決定1・3）。 */
sealed interface CacheInvalidationTarget {
    /** Prompt自身のpublish系イベント。`versionRef`を問わず[promptKey]の全エントリを落とす。 */
    data class DirectPrompt(val promptKey: PromptKey) : CacheInvalidationTarget

    /**
     * Template/Fragmentのpublish/archiveイベント。逆依存（[DependencyRepository.findInboundTemplateOrFragment]）
     * のうち`toVersion`（SemVer範囲）が[semVer]にマッチするPromptだけを落とす。
     */
    data class TemplateOrFragmentVersionChanged(
        val kind: DependencyKind,
        val key: String,
        val semVer: SemVer,
    ) : CacheInvalidationTarget
}

/**
 * `pe.prompt`上のキャッシュ無効化対象イベントの`payload`を[CacheInvalidationTarget]へ復元する
 * （ADR-0033）。
 *
 * **P10b時点の不具合の修正**: 旧実装は`envelope.aggregateId`を`PromptKey`として解釈しようと
 * していたが、`domain_events`由来イベントの`aggregateId`は永続化層のサロゲートキー（UUID）で
 * あり（`DomainEventOutboxSource`のKDoc参照）、`PromptKey`の`namespace/name`形式には
 * 一致しないため常に解釈が失敗し、無効化が無音にスキップされ続けていた。本コーデックは
 * `aggregateId`ではなく`payload`が持つ業務キー（`promptKey`/`templateKey`/`fragmentKey`）
 * から復元する。
 *
 * 解釈できないpayloadは`null`を返す（`ExecutionLogSubscriber`とは異なり例外にしない。
 * キャッシュ無効化はTTL（ADR-0033決定5・b）が既に保険として機能しており、
 * この購読側が起因でDLQを汚す必要性が薄いため。P10b時点からの既存方針を踏襲）。
 */
class CacheInvalidationPayloadCodec(
    private val objectMapper: ObjectMapper,
) {
    fun decode(envelope: EventEnvelope): CacheInvalidationTarget? {
        val node = runCatching { objectMapper.readTree(envelope.payload) }.getOrNull() ?: return null
        return when (envelope.eventType) {
            "PromptPublished", "PromptRolledBack", "PromptArchived", "PromptDiscarded" ->
                textOrNull(node, "promptKey")?.let { runCatching { PromptKey(it) }.getOrNull() }
                    ?.let { CacheInvalidationTarget.DirectPrompt(it) }

            "TemplatePublished", "TemplateArchived" ->
                templateOrFragmentTarget(node, DependencyKind.TEMPLATE, "templateKey")

            "FragmentPublished", "FragmentArchived" ->
                templateOrFragmentTarget(node, DependencyKind.FRAGMENT, "fragmentKey")

            else -> null
        }
    }

    private fun templateOrFragmentTarget(
        node: JsonNode,
        kind: DependencyKind,
        keyField: String,
    ): CacheInvalidationTarget.TemplateOrFragmentVersionChanged? {
        val key = textOrNull(node, keyField)
        val semVer = readSemVer(node)
        return if (key != null && semVer != null) {
            CacheInvalidationTarget.TemplateOrFragmentVersionChanged(kind, key, semVer)
        } else {
            null
        }
    }

    private fun readSemVer(node: JsonNode): SemVer? {
        val semVerNode = node.get("semVer")?.takeIf { it.isObject } ?: return null
        val major = intOrNull(semVerNode, "major")
        val minor = intOrNull(semVerNode, "minor")
        val patch = intOrNull(semVerNode, "patch")
        return if (major != null && minor != null && patch != null) SemVer(major, minor, patch) else null
    }

    private fun intOrNull(
        node: JsonNode,
        field: String,
    ): Int? = node.get(field)?.takeIf { it.isNumber }?.asInt()

    private fun textOrNull(
        node: JsonNode,
        field: String,
    ): String? = node.get(field)?.takeIf { it.isTextual }?.asText()
}
