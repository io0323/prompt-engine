package promptengine.domain.template

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.shared.ExtendsRefApi
import promptengine.domain.shared.SemVer
import promptengine.domain.shared.VersionRange

/**
 * extends参照（key + range）のテスト（ADR-0009）。
 * [ExtendsRef] 自体を主題とするテストのため、クラス単位で[ExtendsRefApi]をOptInする
 * （通常のドメインコードはDSLソースからの導出＝ExtendsFieldMapper経由に限られる）。
 */
@OptIn(ExtendsRefApi::class)
class ExtendsRefTest {
    @Test
    fun `key と range を保持する`() {
        val ref = ExtendsRef(TemplateKey("templates/base-assistant"), VersionRange.CaretMajor(2))

        ref.key shouldBe TemplateKey("templates/base-assistant")
        ref.range shouldBe VersionRange.CaretMajor(2)
    }

    @Test
    fun `range省略時はLatestを既定値とする`() {
        val ref = ExtendsRef(TemplateKey("templates/base-assistant"))

        ref.range shouldBe VersionRange.Latest
    }

    @Test
    fun `同じkeyとrangeを持つExtendsRefは等しい`() {
        val a = ExtendsRef(TemplateKey("templates/base"), VersionRange.Exact(SemVer(1, 3, 0)))
        val b = ExtendsRef(TemplateKey("templates/base"), VersionRange.Exact(SemVer(1, 3, 0)))

        a shouldBe b
    }
}
