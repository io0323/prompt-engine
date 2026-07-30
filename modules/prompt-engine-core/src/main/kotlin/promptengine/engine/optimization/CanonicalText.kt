package promptengine.engine.optimization

/**
 * Context値をToken見積り用の文字列へ変換する（`CompressionRule`/`ContextOptimizationRule`共用）。
 *
 * `Map`/`Set`のデフォルト`toString()`は反復順序を保証しない実装によっては値が変わりうるため、
 * `Map`は[Map.entries]をキーでソートしてから、`Set`は要素を文字列化した後にソートしてから
 * 連結する（ADR-0013決定2「Map/Set反復順序に依存しない」の一環。この文字列自体は
 * renderHashには寄与しないが、見積りに使うトークン数がMap/Setの内部実装差で変動すると、
 * 切り詰め件数がぶれてrenderHashの決定性を間接的に損ないうるため）。
 */
internal fun canonicalString(value: Any?): String =
    when (value) {
        is Map<*, *> ->
            value.entries
                .sortedBy { it.key.toString() }
                .joinToString(separator = ",") { "${it.key}=${canonicalString(it.value)}" }
        is Set<*> -> value.map { canonicalString(it) }.sorted().joinToString(separator = ",")
        is List<*> -> value.joinToString(separator = ",") { canonicalString(it) }
        else -> value?.toString() ?: ""
    }
