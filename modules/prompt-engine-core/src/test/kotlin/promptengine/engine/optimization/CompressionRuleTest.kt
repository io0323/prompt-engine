package promptengine.engine.optimization

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.composition.CompiledPrompt
import promptengine.domain.context.ContextBindingSet
import promptengine.domain.optimization.ModelProfile
import promptengine.domain.shared.Cost
import promptengine.domain.shared.SensitiveValue
import promptengine.domain.shared.TokenCount
import promptengine.domain.tokenizer.TokenizerPlugin
import java.math.BigDecimal

class CompressionRuleTest {
    private val tokenizer = TokenizerPlugin { text -> TokenCount(text.length) }
    private val rule = CompressionRule(tokenizer)
    private val profile = ModelProfile(TokenCount(8000), "approx-v1", Cost(BigDecimal.ZERO))
    private val emptyCompiled = CompiledPrompt(emptyList(), emptyList(), emptyList(), emptyList())

    @Test
    fun `見積りが予算以下ならapplicableでない`() {
        rule.applicable(
            emptyCompiled,
            ContextBindingSet.empty(),
            profile,
            TokenCount(10),
            TokenCount(10),
        ) shouldBe false
    }

    @Test
    fun `見積りが予算を超えるとapplicable`() {
        rule.applicable(emptyCompiled, ContextBindingSet.empty(), profile, TokenCount(11), TokenCount(10)) shouldBe true
    }

    @Test
    fun `予算内ならoptimizeは何も変更しない`() {
        val contextBindings = ContextBindingSet(mapOf("conversation.messages" to listOf("m1")))

        val result = rule.optimize(emptyCompiled, contextBindings, profile, TokenCount(5), TokenCount(10))

        result.contextBindings shouldBe contextBindings
        result.note.tokensSaved shouldBe TokenCount(0)
        result.truncations shouldBe emptyList()
    }

    @Test
    fun `conversationを古い順から間引く`() {
        val contextBindings = ContextBindingSet(mapOf("conversation.messages" to listOf("m1", "m2", "m3", "m4")))

        // 各要素2文字=2トークン、estimatedTokens=20, budget=14 -> 6トークン分間引く必要
        val result = rule.optimize(emptyCompiled, contextBindings, profile, TokenCount(20), TokenCount(14))

        @Suppress("UNCHECKED_CAST")
        val remaining = result.contextBindings.values["conversation.messages"] as List<String>
        remaining shouldBe listOf("m4")
        result.note.tokensSaved shouldBe TokenCount(6)
        result.truncations.single().scope shouldBe "conversation"
        result.truncations.single().summary shouldBe "dropped 3 oldest of 4 entries from conversation.messages"
    }

    @Test
    fun `conversationを使い切ってからmemoryへ進む`() {
        val contextBindings =
            ContextBindingSet(
                mapOf(
                    "conversation.messages" to listOf("a", "b"),
                    "memory.entries" to listOf("x", "y", "z"),
                ),
            )

        val result = rule.optimize(emptyCompiled, contextBindings, profile, TokenCount(10), TokenCount(3))

        @Suppress("UNCHECKED_CAST")
        val conversation = result.contextBindings.values["conversation.messages"] as List<String>

        @Suppress("UNCHECKED_CAST")
        val memory = result.contextBindings.values["memory.entries"] as List<String>
        conversation shouldBe emptyList()
        memory shouldBe emptyList()
        result.truncations.map { it.scope } shouldBe listOf("conversation", "memory")
        result.note.tokensSaved shouldBe TokenCount(5)
    }

    @Test
    fun `summaryには実際の内容を含めない`() {
        val contextBindings = ContextBindingSet(mapOf("conversation.messages" to listOf("secret-content")))

        val result = rule.optimize(emptyCompiled, contextBindings, profile, TokenCount(100), TokenCount(1))

        result.truncations.single().summary shouldBe "dropped 1 oldest of 1 entries from conversation.messages"
    }

    @Test
    fun `sensitive値を含む会話履歴を切り詰めてもsummaryに実値は含めない`() {
        val contextBindings =
            ContextBindingSet(mapOf("conversation.messages" to listOf(SensitiveValue.of("sk-real-secret"))))

        val result = rule.optimize(emptyCompiled, contextBindings, profile, TokenCount(100), TokenCount(1))

        val summary = result.truncations.single().summary
        summary.contains("sk-real-secret") shouldBe false
    }

    @Test
    fun `scope接頭辞に一致してもList値でなければ間引き対象にしない`() {
        val contextBindings =
            ContextBindingSet(
                mapOf(
                    "conversation.summary" to "not a list",
                    "conversation.messages" to listOf("m1"),
                ),
            )

        val result = rule.optimize(emptyCompiled, contextBindings, profile, TokenCount(100), TokenCount(1))

        result.contextBindings.values["conversation.summary"] shouldBe "not a list"
        result.truncations.single().summary shouldBe "dropped 1 oldest of 1 entries from conversation.messages"
    }

    @Test
    fun `間引き対象が1件も無ければdetailはno truncatable entries found`() {
        val contextBindings = ContextBindingSet(mapOf("application.channel" to "web"))

        val result = rule.optimize(emptyCompiled, contextBindings, profile, TokenCount(100), TokenCount(1))

        result.note.detail shouldBe "no truncatable entries found"
        result.truncations shouldBe emptyList()
    }

    @Test
    fun `同一scope内で不足分を満たしたら後続のkeyは処理しない`() {
        val contextBindings =
            ContextBindingSet(
                mapOf(
                    // "aaa"はソート順で"bbb"より先に処理される。1件で不足分(2)を満たすため
                    // "conversation.bbb"は未処理のまま残るはず
                    "conversation.aaa" to listOf("xy"),
                    "conversation.bbb" to listOf("z"),
                ),
            )

        val result = rule.optimize(emptyCompiled, contextBindings, profile, TokenCount(2), TokenCount(0))

        result.contextBindings.values["conversation.aaa"] shouldBe emptyList<String>()
        result.contextBindings.values["conversation.bbb"] shouldBe listOf("z")
        result.truncations.map { it.summary } shouldBe listOf("dropped 1 oldest of 1 entries from conversation.aaa")
    }

    @Test
    fun `既に空のListは間引き対象にならない`() {
        val contextBindings = ContextBindingSet(mapOf("conversation.messages" to emptyList<String>()))

        val result = rule.optimize(emptyCompiled, contextBindings, profile, TokenCount(100), TokenCount(1))

        result.note.detail shouldBe "no truncatable entries found"
        result.truncations shouldBe emptyList()
    }
}
