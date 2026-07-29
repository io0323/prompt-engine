package promptengine.engine.compiler

import promptengine.domain.composition.CompositionMode
import promptengine.domain.shared.PublicationState
import promptengine.domain.shared.SemVer
import promptengine.domain.shared.VersionRange

/**
 * SemVer範囲・Status検証によるVersion選択（設計書§2.10、ADR-0009決定5・6）。
 * `ReferenceResolver`（Template extends）と`FragmentResolver`（Fragment import/include）の
 * どちらからも共通で使う（ADR-0009の「同等の仕組みを新たに作らないこと」指示に対応、
 * ADR-0010影響範囲）。純粋関数であり、同じ入力に対して常に同じ結果を返す（決定性）。
 */
internal object VersionSelector {
    data class VersionInfo(val semVer: SemVer, val state: PublicationState)

    class Query(val range: VersionRange, val mode: CompositionMode)

    /** 候補が見つからない/Draftしか無く許可されない場合の失敗経路（いずれも`Nothing`を返す＝例外を投げる想定）。 */
    class FailureHandlers(val onNotFound: () -> Nothing, val onDraftNotAllowed: () -> Nothing)

    /** [versions]のうち[query]の範囲にマッチし、かつ[query]のmodeが許すStatusの中で最大のVersionを選ぶ。 */
    fun <V> select(
        versions: List<V>,
        info: (V) -> VersionInfo,
        query: Query,
        handlers: FailureHandlers,
    ): V {
        val rangeMatches = versions.filter { query.range.matches(info(it).semVer) }
        if (rangeMatches.isEmpty()) handlers.onNotFound()

        val allowedStatuses =
            if (query.mode == CompositionMode.COMPILE_ONLY) {
                setOf(PublicationState.Published, PublicationState.Draft)
            } else {
                setOf(PublicationState.Published)
            }
        val eligible = rangeMatches.filter { info(it).state in allowedStatuses }
        if (eligible.isNotEmpty()) return eligible.maxBy { info(it).semVer }
        if (rangeMatches.any { info(it).state == PublicationState.Draft }) handlers.onDraftNotAllowed()
        handlers.onNotFound()
    }
}
