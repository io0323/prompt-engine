package promptengine.domain.execution

/**
 * parse失敗時の修復再実行ポリシー（設計書§2.6ステージ10「PARSE_FAILED（リトライ/修復Policy適用可）」、
 * ADR-0014決定8）。
 *
 * 既定は[enabled]=falseで無効。修復再実行は追加の課金・レイテンシを伴う操作であり、
 * 呼出側が明示的に許可した場合のみ行う。[maxAttempts]の既定値2は、1回では
 * 「たまたま外れた」ケースを救えず、無制限では課金が青天井になるため、実用上のバランスとして採用する
 * （初回のparseは含まない、修復のための再実行回数の上限）。
 */
data class ParseRepairPolicy(
    val enabled: Boolean = false,
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) {
    init {
        require(maxAttempts >= 0) { "maxAttempts must not be negative: $maxAttempts" }
    }

    companion object {
        private const val DEFAULT_MAX_ATTEMPTS = 2
    }
}
