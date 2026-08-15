package promptengine.infrastructure.subscriber

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.dependency.DependencyKind
import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.SemVer

/**
 * [CacheInvalidationPayloadCodec]の単体テスト（ADR-0033）。
 * [CacheAndSearchSubscriberTest]は購読側を通した振る舞いを検証するが、本テストは
 * 解釈できないpayload（不正JSON・欠損フィールド）でも`null`を返し例外にしないという
 * コーデック自身の契約を、境界値ごとに直接固定する。
 */
class CacheInvalidationPayloadCodecTest {
    private val codec = CacheInvalidationPayloadCodec(testObjectMapper)

    @Test
    fun `PromptPublishedはpromptKeyとsemVerが揃っていればDirectPromptを返す`() {
        val target = codec.decode(envelope(eventType = "PromptPublished", payload = promptKeyedPayload()))

        target shouldBe CacheInvalidationTarget.DirectPrompt(PromptKey("support/faq"))
    }

    @Test
    fun `不正なJSONはnullを返す`() {
        val target = codec.decode(envelope(eventType = "PromptPublished", payload = "not json"))

        target shouldBe null
    }

    @Test
    fun `promptKeyフィールドが無いPromptPublishedはnullを返す`() {
        val target =
            codec.decode(
                envelope(eventType = "PromptPublished", payload = """{"semVer":{"major":1,"minor":0,"patch":0}}"""),
            )

        target shouldBe null
    }

    @Test
    fun `promptKeyの値がPromptKeyとして不正な形式ならnullを返す`() {
        val target =
            codec.decode(
                envelope(
                    eventType = "PromptPublished",
                    payload = """{"promptKey":"not a valid prompt key!!"}""",
                ),
            )

        target shouldBe null
    }

    @Test
    fun `TemplatePublishedはtemplateKeyとsemVerが揃っていればTemplateOrFragmentVersionChangedを返す`() {
        val target =
            codec.decode(
                envelope(
                    eventType = "TemplatePublished",
                    payload =
                        promptKeyedPayload(
                            field = "templateKey",
                            key = "team/base",
                            major = 2,
                            minor = 1,
                            patch = 0,
                        ),
                ),
            )

        val expected =
            CacheInvalidationTarget.TemplateOrFragmentVersionChanged(
                DependencyKind.TEMPLATE,
                "team/base",
                SemVer(2, 1, 0),
            )
        target shouldBe expected
    }

    @Test
    fun `FragmentPublishedはfragmentKeyとsemVerが揃っていればTemplateOrFragmentVersionChangedを返す`() {
        val target =
            codec.decode(
                envelope(
                    eventType = "FragmentPublished",
                    payload =
                        promptKeyedPayload(
                            field = "fragmentKey",
                            key = "team/notice",
                            major = 1,
                            minor = 0,
                            patch = 0,
                        ),
                ),
            )

        val expected =
            CacheInvalidationTarget.TemplateOrFragmentVersionChanged(
                DependencyKind.FRAGMENT,
                "team/notice",
                SemVer(1, 0, 0),
            )
        target shouldBe expected
    }

    @Test
    fun `TemplatePublishedでtemplateKeyが無ければnullを返す`() {
        val target =
            codec.decode(
                envelope(eventType = "TemplatePublished", payload = """{"semVer":{"major":1,"minor":0,"patch":0}}"""),
            )

        target shouldBe null
    }

    @Test
    fun `TemplatePublishedでsemVerオブジェクト自体が無ければnullを返す`() {
        val target =
            codec.decode(
                envelope(eventType = "TemplatePublished", payload = """{"templateKey":"team/base"}"""),
            )

        target shouldBe null
    }

    @Test
    fun `semVerの一部フィールド minor欠損 だけが無くてもnullを返す`() {
        val target =
            codec.decode(
                envelope(
                    eventType = "TemplatePublished",
                    payload = """{"templateKey":"team/base","semVer":{"major":1,"patch":0}}""",
                ),
            )

        target shouldBe null
    }

    @Test
    fun `semVerがオブジェクトでなければnullを返す`() {
        val target =
            codec.decode(
                envelope(eventType = "TemplatePublished", payload = """{"templateKey":"team/base","semVer":"1.0.0"}"""),
            )

        target shouldBe null
    }

    @Test
    fun `未対応のeventTypeはnullを返す`() {
        val target = codec.decode(envelope(eventType = "PromptValidated", payload = promptKeyedPayload()))

        target shouldBe null
    }
}
