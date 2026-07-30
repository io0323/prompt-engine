package promptengine.engine

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * `engine.render`/`engine.optimization`が決定性を壊しうるAPIを直接使っていないことを検証する
 * （ADR-0013決定2「構造的に起こらないようにする」）。
 *
 * ArchUnitは「特定の引数無しオーバーロードの呼出のみを禁止する」ような細かい呼出条件を
 * 素直には表現できないため（`uppercase()`と`uppercase(Locale)`はコンパイル後のシグネチャが
 * 異なる別メソッド）、ソーステキストの走査で代替する。`bootstrap`の`ArchitectureTest`
 * （モジュール境界・パッケージ依存の検証）とは目的が異なる、`prompt-engine-core`自身の
 * ソースに対する構造的歯止め。
 */
class DeterminismGuardTest {
    private val targetDirs =
        listOf(
            "src/main/kotlin/promptengine/engine/render",
            "src/main/kotlin/promptengine/engine/optimization",
        )

    @Test
    fun `ロケール引数無しの大小文字変換を使わない`() {
        val forbidden =
            listOf(
                Regex("""\.uppercase\(\)"""),
                Regex("""\.lowercase\(\)"""),
                Regex("""\btoUpperCase\(\)"""),
                Regex("""\btoLowerCase\(\)"""),
            )

        violations(forbidden) shouldBe emptyList()
    }

    @Test
    fun `現在時刻 乱数をContext経由以外で直接使わない`() {
        val forbidden =
            listOf(
                Regex("""Instant\.now\("""),
                Regex("""System\.currentTimeMillis\("""),
                Regex("""LocalDate(Time)?\.now\("""),
                Regex("""kotlin\.random\.Random"""),
                Regex("""java\.util\.Random"""),
                Regex("""\bRandom\.Default\b"""),
            )

        violations(forbidden) shouldBe emptyList()
    }

    @Test
    fun `反復順序を保証しないHashMap HashSetを使わない`() {
        // mapOf/toMap/toMutableMap/associate/toSet/setOfはいずれもKotlin標準ライブラリの
        // 実装上LinkedHashMap/LinkedHashSetを内部で使うため挿入順を保持する。生の
        // HashMap/HashSet（java.util.HashMap/HashSet、hashMapOf/hashSetOf）はハッシュ
        // バケット順に依存し、データ量やハッシュ衝突で反復順が変わりうるため禁止する。
        val forbidden =
            listOf(
                Regex("""\bHashMap\b"""),
                Regex("""\bHashSet\b"""),
                Regex("""\bhashMapOf\b"""),
                Regex("""\bhashSetOf\b"""),
            )

        violations(forbidden) shouldBe emptyList()
    }

    private fun violations(patterns: List<Regex>): List<String> {
        val results = mutableListOf<String>()
        targetDirs.forEach { dir ->
            val root = File(dir)
            if (!root.exists()) return@forEach
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    file.readLines().forEachIndexed { index, line ->
                        patterns.forEach { pattern ->
                            if (pattern.containsMatchIn(line)) {
                                results += "${file.path}:${index + 1}: ${line.trim()}"
                            }
                        }
                    }
                }
        }
        return results
    }
}
