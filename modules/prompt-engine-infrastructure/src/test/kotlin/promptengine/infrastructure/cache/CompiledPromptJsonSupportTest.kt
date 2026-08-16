package promptengine.infrastructure.cache

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.composition.ResolvedDependency
import promptengine.domain.shared.PublicationState
import promptengine.domain.shared.SemVer
import promptengine.domain.shared.VersionRange
import promptengine.domain.template.TemplateKey

/**
 * [compiledPromptObjectMapper]のmixin群のうち、[RedisPromptCacheIntegrationTest]（成功経路のみ）
 * ではカバーされない異常系（不正な`PublicationState`文字列）を単体で固定する。
 */
class CompiledPromptJsonSupportTest {
    private val mapper = compiledPromptObjectMapper(jacksonObjectMapper())

    @Test
    fun `PublicationStateは3種の値を文字列として往復できる`() {
        listOf(PublicationState.Draft, PublicationState.Published, PublicationState.Archived).forEach { state ->
            val dependency =
                ResolvedDependency.TemplateDependency(
                    key = TemplateKey("shared/base"),
                    requestedRange = VersionRange.Latest,
                    resolvedVersion = SemVer(1, 0, 0),
                    status = state,
                    contentHash = "a".repeat(64),
                )

            val json = mapper.writeValueAsString(dependency)
            val restored = mapper.readValue(json, ResolvedDependency::class.java)

            restored shouldBe dependency
        }
    }

    @Test
    fun `未知のPublicationState文字列は例外を投げる`() {
        val json =
            """
            {"@type":"TemplateDependency","key":{"value":"shared/base"},"requestedRange":null,
             "resolvedVersion":{"major":1,"minor":0,"patch":0},"status":"Bogus",
             "contentHash":"${"a".repeat(64)}"}
            """.trimIndent()

        shouldThrow<Exception> {
            mapper.readValue(json, ResolvedDependency::class.java)
        }
    }
}
