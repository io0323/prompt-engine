package promptengine.engine.benchmark

import java.text.Normalizer
import java.util.Locale

/**
 * [NormalizedExactMatchScoringRule]（Accuracy）と[ConsistencyScoringRule]が共有する正規化規則
 * （NFC正規化 → trim → `Locale.ROOT`での大文字小文字無視、[NormalizedExactMatchScoringRule]の
 * KDoc参照）。`internal`のため`prompt-engine-core`モジュール外からは参照できない。
 */
internal fun normalizeForComparison(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFC).trim().lowercase(Locale.ROOT)
