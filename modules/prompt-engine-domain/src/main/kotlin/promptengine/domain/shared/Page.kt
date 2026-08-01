package promptengine.domain.shared

/**
 * 検索系Queryの結果を包むページ表現（設計書§13共通仕様のページング、ADR-0017）。
 */
data class Page<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
) {
    init {
        require(page >= 0) { "page must not be negative: $page" }
        require(size in 1..MAX_SIZE) { "size must be between 1 and $MAX_SIZE: $size" }
        require(totalElements >= 0) { "totalElements must not be negative: $totalElements" }
    }

    companion object {
        const val DEFAULT_SIZE = 20
        const val MAX_SIZE = 100
    }
}
