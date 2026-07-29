package promptengine.engine.resolver

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.context.ContextRequirement
import promptengine.domain.context.ContextUnavailableException
import promptengine.domain.shared.PromptRequest

class ContextResolverImplTest {
    @Test
    fun `マージ順序は宣言の並び順ではなく environment system application workflow user memory conversation の固定順で処理される`() {
        // requirementsの並び順はわざとMERGE_ORDERと逆・不揃いにする。処理順は
        // ContextResolverImplが持つ固定順（設計書§2.7）に従うべきで、リストの並びに
        // 引きずられないことを検証する。キー自体は"$scope.$path"でスコープ名前空間化
        // されているため値の衝突は原則起きない（§2.7）。ここではその前提のもとで
        // 「処理順」＝values挿入順を検証する。
        val requirements =
            listOf(
                ContextRequirement(scope = "conversation", required = listOf("summary")),
                ContextRequirement(scope = "environment", required = listOf("region")),
                ContextRequirement(scope = "user", required = listOf("id")),
            )
        val request =
            PromptRequest(
                contextData =
                    mapOf(
                        "conversation" to mapOf("summary" to "prior turns summary"),
                        "environment" to mapOf("region" to "ap-northeast-1"),
                        "user" to mapOf("id" to "user-1"),
                    ),
            )
        val resolver = ContextResolverImpl.standard()

        val result = resolver.resolve(requirements, request)

        result.values.keys.toList() shouldBe listOf("environment.region", "user.id", "conversation.summary")
    }

    @Test
    fun `requiredなpathが複数解決できない場合は全て列挙されてContextUnavailableExceptionになる`() {
        val requirements =
            listOf(
                ContextRequirement(scope = "user", required = listOf("id", "email")),
                ContextRequirement(scope = "system", required = listOf("locale")),
            )
        val request = PromptRequest(contextData = mapOf("user" to mapOf("id" to "user-1")))
        val resolver = ContextResolverImpl.standard()

        val exception = shouldThrow<ContextUnavailableException> { resolver.resolve(requirements, request) }

        exception.missingRequirements shouldBe listOf("system.locale", "user.email")
    }

    @Test
    fun `optionalなpathが解決できない場合は例外にせずwarningsに積んで継続する`() {
        val requirements =
            listOf(ContextRequirement(scope = "user", required = listOf("id"), optional = listOf("locale")))
        val request = PromptRequest(contextData = mapOf("user" to mapOf("id" to "user-1")))
        val resolver = ContextResolverImpl.standard()

        val result = resolver.resolve(requirements, request)

        result.values shouldBe mapOf("user.id" to "user-1")
        result.warnings shouldBe listOf("optional context not resolved: user.locale")
    }

    @Test
    fun `宣言されていないスコープはデータがあっても注入されない`() {
        val requirements = listOf(ContextRequirement(scope = "user", required = listOf("id")))
        val request =
            PromptRequest(
                contextData =
                    mapOf(
                        "user" to mapOf("id" to "user-1"),
                        "system" to mapOf("locale" to "ja-JP"),
                    ),
            )
        val resolver = ContextResolverImpl.standard()

        val result = resolver.resolve(requirements, request)

        result.values shouldBe mapOf("user.id" to "user-1")
    }

    @Test
    fun `requirementsが空なら空のContextBindingSetを返す`() {
        val resolver = ContextResolverImpl.standard()

        val result = resolver.resolve(emptyList(), PromptRequest())

        result.values shouldBe emptyMap()
        result.warnings shouldBe emptyList()
    }
}
