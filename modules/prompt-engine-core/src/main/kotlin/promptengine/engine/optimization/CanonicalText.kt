package promptengine.engine.optimization

/**
 * Context値をToken見積り用の文字列へ変換する（`CompressionRule`/`ContextOptimizationRule`共用）。
 *
 * `Map`のデフォルト`toString()`は反復順序を保証しない実装によっては値が変わりうるため、
 * [Map.entries]をキーでソートしてから連結する（ADR-0013決定2「Map/Set反復順序に
 * 依存しない」の一環。この文字列自体はrenderHashには寄与しないが、見積りに使う
 * トークン数がMapの内部実装差で変動すると、切り詰め件数がぶれてrenderHashの決定性を
 * 間接的に損ないうるため）。
 */
internal fun canonicalString(value: Any?): String =
    when (value) {
        is Map<*, *> ->
            value.entries
                .sortedBy { it.key.toString() }
                .joinToString(separator = ",") { "${it.key}=${canonicalString(it.value)}" }
        is List<*> -> value.joinToString(separator = ",") { canonicalString(it) }
        else -> value?.toString() ?: ""
    }
