package promptengine.engine.resolver

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import promptengine.domain.shared.SensitiveValue
import promptengine.domain.variable.SecretManagerAdapter
import promptengine.domain.variable.VariableDefinition
import promptengine.domain.variable.VariableSource
import promptengine.domain.variable.VariableType
import promptengine.domain.variable.VariableUnresolvedException

private const val SECRET_VALUE = "sk-live-do-not-leak-12345"

class VariableResolverChainTest {
    private class FakeSecretManagerAdapter(
        private val secrets: Map<String, String> = emptyMap(),
        private val failing: Set<String> = emptySet(),
    ) : SecretManagerAdapter {
        override fun getSecret(name: String): SensitiveValue? {
            if (name in failing) throw SecretManagerUnavailableException(name)
            return secrets[name]?.let { SensitiveValue.of(it) }
        }
    }

    private class SecretManagerUnavailableException(name: String) : RuntimeException(
        "secret manager unreachable: $name",
    )

    private fun chainWithSecrets(
        secrets: Map<String, String> = emptyMap(),
        failing: Set<String> = emptySet(),
    ): VariableResolverChain = VariableResolverChain.standard(FakeSecretManagerAdapter(secrets, failing))

    // ---- 優先順位: 6種のResolverが競合する全組合せ ----

    @Test
    fun `各sourceは対応するストアの値のみを見て 他ストアに同名の値があっても解決しない`() {
        // source=USER/WORKFLOW/ENVIRONMENTそれぞれについて、自分のストア以外の2ストアに
        // 同名の"decoy"値を仕込んでも解決されない（宣言と異なる経路からの偶然の解決を防ぐ、ADR-0011）。
        val sourcesToTest = listOf(VariableSource.USER, VariableSource.WORKFLOW, VariableSource.ENVIRONMENT)

        for (source in sourcesToTest) {
            val definition =
                VariableDefinition(name = "x", type = VariableType.STRING, source = source, required = true)
            val decoy = mapOf("x" to "decoy")
            val decoyRequest =
                PromptRequest(
                    userVariables = if (source == VariableSource.USER) emptyMap() else decoy,
                    workflowVariables = if (source == VariableSource.WORKFLOW) emptyMap() else decoy,
                    environmentVariables = if (source == VariableSource.ENVIRONMENT) emptyMap() else decoy,
                )
            val chain = chainWithSecrets()

            shouldThrow<VariableUnresolvedException> { chain.resolveAll(listOf(definition), decoyRequest) }
                .missingNames shouldBe listOf("x")
        }
    }

    @Test
    fun `source=STATICはdefaultから解決される`() {
        val definition =
            VariableDefinition(
                name = "tone",
                type = VariableType.STRING,
                source = VariableSource.STATIC,
                default = "polite",
            )
        val chain = chainWithSecrets()

        val bindings = chain.resolveAll(listOf(definition), PromptRequest())

        bindings["tone"] shouldBe "polite"
    }

    @Test
    fun `source=USERはuserVariablesから解決される`() {
        val definition =
            VariableDefinition(name = "displayName", type = VariableType.STRING, source = VariableSource.USER)
        val chain = chainWithSecrets()

        val bindings =
            chain.resolveAll(
                listOf(definition),
                PromptRequest(userVariables = mapOf("displayName" to "Alice")),
            )

        bindings["displayName"] shouldBe "Alice"
    }

    @Test
    fun `source=WORKFLOWはworkflowVariablesから解決される`() {
        val definition =
            VariableDefinition(name = "stepId", type = VariableType.STRING, source = VariableSource.WORKFLOW)
        val chain = chainWithSecrets()

        val bindings =
            chain.resolveAll(
                listOf(definition),
                PromptRequest(workflowVariables = mapOf("stepId" to "step-3")),
            )

        bindings["stepId"] shouldBe "step-3"
    }

    @Test
    fun `source=ENVIRONMENTはenvironmentVariablesから解決される`() {
        val definition =
            VariableDefinition(name = "region", type = VariableType.STRING, source = VariableSource.ENVIRONMENT)
        val chain = chainWithSecrets()

        val bindings =
            chain.resolveAll(
                listOf(definition),
                PromptRequest(environmentVariables = mapOf("region" to "ap-northeast-1")),
            )

        bindings["region"] shouldBe "ap-northeast-1"
    }

    @Test
    fun `source=RUNTIMEはexplicitParametersでしか解決されない`() {
        val definition =
            VariableDefinition(
                name = "question",
                type = VariableType.STRING,
                source = VariableSource.RUNTIME,
                required = true,
            )
        val chain = chainWithSecrets()

        shouldThrow<VariableUnresolvedException> {
            chain.resolveAll(listOf(definition), PromptRequest(userVariables = mapOf("question" to "decoy")))
        }

        val bindings =
            chain.resolveAll(
                listOf(definition),
                PromptRequest(explicitParameters = mapOf("question" to "hi")),
            )
        bindings["question"] shouldBe "hi"
    }

    @Test
    fun `Explicit Parameterは宣言されたsourceに関わらず常に最優先される`() {
        val explicit = mapOf("x" to "explicit")
        val sources =
            listOf(
                VariableSource.STATIC to PromptRequest(explicitParameters = explicit),
                VariableSource.USER to
                    PromptRequest(explicitParameters = explicit, userVariables = mapOf("x" to "user-store")),
                VariableSource.WORKFLOW to
                    PromptRequest(explicitParameters = explicit, workflowVariables = mapOf("x" to "wf-store")),
                VariableSource.ENVIRONMENT to
                    PromptRequest(explicitParameters = explicit, environmentVariables = mapOf("x" to "env-store")),
            )

        for ((source, request) in sources) {
            val definition =
                VariableDefinition(
                    name = "x",
                    type = VariableType.STRING,
                    source = source,
                    default = if (source == VariableSource.STATIC) "static-default" else null,
                )
            val chain = chainWithSecrets()

            chain.resolveAll(listOf(definition), request)["x"] shouldBe "explicit"
        }
    }

    @Test
    fun `source=SECRETはSecretManagerAdapter経由でのみ解決される`() {
        val definition =
            VariableDefinition(
                name = "apiKeyRef",
                type = VariableType.STRING,
                source = VariableSource.SECRET,
                required = true,
                sensitive = true,
            )
        val chain = chainWithSecrets(secrets = mapOf("apiKeyRef" to SECRET_VALUE))

        val bindings =
            chain.resolveAll(
                listOf(definition),
                PromptRequest(userVariables = mapOf("apiKeyRef" to "decoy")),
            )

        (bindings["apiKeyRef"] as SensitiveValue).expose() shouldBe SECRET_VALUE
    }

    // ---- 未解決: requiredが複数未解決のとき全件列挙 ----

    @Test
    fun `requiredな変数が複数未解決のとき全て列挙されて例外になる`() {
        val definitions =
            listOf(
                VariableDefinition(
                    name = "a",
                    type = VariableType.STRING,
                    source = VariableSource.USER,
                    required = true,
                ),
                VariableDefinition(
                    name = "b",
                    type = VariableType.STRING,
                    source = VariableSource.WORKFLOW,
                    required = true,
                ),
                VariableDefinition(
                    name = "c",
                    type = VariableType.STRING,
                    source = VariableSource.STATIC,
                    required = false,
                ),
                VariableDefinition(
                    name = "d",
                    type = VariableType.STRING,
                    source = VariableSource.SECRET,
                    required = true,
                    sensitive = true,
                ),
            )
        val chain = chainWithSecrets()

        val exception = shouldThrow<VariableUnresolvedException> { chain.resolveAll(definitions, PromptRequest()) }

        exception.missingNames shouldBe listOf("a", "b", "d")
    }

    @Test
    fun `requiredでない変数が未解決でもBindingSetから省かれるだけで例外にならない`() {
        val definition =
            VariableDefinition(
                name = "optionalTone",
                type = VariableType.STRING,
                source = VariableSource.USER,
                required = false,
            )
        val chain = chainWithSecrets()

        val bindings = chain.resolveAll(listOf(definition), PromptRequest())

        bindings.containsKey("optionalTone") shouldBe false
    }

    // ---- Secret解決失敗の分類: 未設定 vs インフラ障害 ----

    @Test
    fun `SecretManagerAdapterがnullを返す場合は他の未解決変数と同様にVARIABLE_UNRESOLVEDへ含まれる`() {
        val definition =
            VariableDefinition(
                name = "apiKeyRef",
                type = VariableType.STRING,
                source = VariableSource.SECRET,
                required = true,
                sensitive = true,
            )
        val chain = chainWithSecrets(secrets = emptyMap())

        val exception =
            shouldThrow<VariableUnresolvedException> { chain.resolveAll(listOf(definition), PromptRequest()) }

        exception.missingNames shouldBe listOf("apiKeyRef")
    }

    @Test
    fun `SecretManagerAdapterが例外を投げる場合はVariableResolverChainがそのまま伝播させVARIABLE_UNRESOLVEDにはならない`() {
        val definition =
            VariableDefinition(
                name = "apiKeyRef",
                type = VariableType.STRING,
                source = VariableSource.SECRET,
                required = true,
                sensitive = true,
            )
        val chain = chainWithSecrets(failing = setOf("apiKeyRef"))

        shouldThrow<SecretManagerUnavailableException> { chain.resolveAll(listOf(definition), PromptRequest()) }
    }

    // ---- Secret漏洩経路: BindingSet.toString / 例外メッセージ / ログ経路 / キャッシュキー経路 ----

    @Test
    fun `漏洩経路1 BindingSet toStringに秘匿値の実値が含まれない`() {
        val definition =
            VariableDefinition(
                name = "apiKeyRef",
                type = VariableType.STRING,
                source = VariableSource.SECRET,
                required = true,
                sensitive = true,
            )
        val chain = chainWithSecrets(secrets = mapOf("apiKeyRef" to SECRET_VALUE))

        val bindings = chain.resolveAll(listOf(definition), PromptRequest())

        bindings.toString() shouldNotContain SECRET_VALUE
        bindings.toString() shouldContain "apiKeyRef"
    }

    @Test
    fun `漏洩経路2 未解決に伴う例外メッセージに秘匿値の実値が含まれない`() {
        val resolved =
            VariableDefinition(
                name = "apiKeyRef",
                type = VariableType.STRING,
                source = VariableSource.SECRET,
                required = true,
                sensitive = true,
            )
        val unresolved =
            VariableDefinition(
                name = "otherSecretRef",
                type = VariableType.STRING,
                source = VariableSource.SECRET,
                required = true,
                sensitive = true,
            )
        val chain = chainWithSecrets(secrets = mapOf("apiKeyRef" to SECRET_VALUE))

        val exception =
            shouldThrow<VariableUnresolvedException> {
                chain.resolveAll(listOf(resolved, unresolved), PromptRequest())
            }

        exception.message.orEmpty() shouldNotContain SECRET_VALUE
        exception.message.orEmpty() shouldContain "otherSecretRef"
    }

    @Test
    fun `漏洩経路3 ログ相当のstring interpolationに秘匿値の実値が含まれない`() {
        val definition =
            VariableDefinition(
                name = "apiKeyRef",
                type = VariableType.STRING,
                source = VariableSource.SECRET,
                required = true,
                sensitive = true,
            )
        val chain = chainWithSecrets(secrets = mapOf("apiKeyRef" to SECRET_VALUE))

        val bindings = chain.resolveAll(listOf(definition), PromptRequest())
        // ログ出力は値を直接連結・補間するのが典型的な実装（例: logger.info("bindings=$bindings")）。
        // その場合に呼ばれるtoString()経路を模したもの。
        val simulatedLogLine = "resolved bindings=${bindings.values}"

        simulatedLogLine shouldNotContain SECRET_VALUE
    }

    @Test
    fun `漏洩経路4 キャッシュキー相当の文字列合成に秘匿値の実値が含まれない`() {
        val definition =
            VariableDefinition(
                name = "apiKeyRef",
                type = VariableType.STRING,
                source = VariableSource.SECRET,
                required = true,
                sensitive = true,
            )
        val chain = chainWithSecrets(secrets = mapOf("apiKeyRef" to SECRET_VALUE))

        val bindings = chain.resolveAll(listOf(definition), PromptRequest())
        // キャッシュキー生成は典型的にbinding値を連結する（例: values.entries.joinToString{"${it.key}=${it.value}"}）。
        val simulatedCacheKey = bindings.values.entries.joinToString(separator = "&") { "${it.key}=${it.value}" }

        simulatedCacheKey shouldNotContain SECRET_VALUE
    }

    // ---- companion factory ----

    @Test
    fun `standardは6種のResolverを設計書§2 8の優先順位で組む`() {
        val chain =
            VariableResolverChain.standard(
                FakeSecretManagerAdapter(secrets = mapOf("apiKeyRef" to SECRET_VALUE)),
            )
        val definition =
            VariableDefinition(
                name = "apiKeyRef",
                type = VariableType.STRING,
                source = VariableSource.SECRET,
                required = true,
                sensitive = true,
            )

        val bindings =
            chain.resolveAll(
                listOf(definition),
                PromptRequest(explicitParameters = mapOf("apiKeyRef" to "explicit-override")),
            )

        (bindings["apiKeyRef"] as SensitiveValue).expose() shouldBe "explicit-override"
    }
}
