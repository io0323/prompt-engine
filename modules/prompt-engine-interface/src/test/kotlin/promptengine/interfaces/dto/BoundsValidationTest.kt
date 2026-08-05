package promptengine.interfaces.dto

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import jakarta.validation.Validation
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * ページング・実行ポリシー系DTOの境界値検証（CodeRabbitレビュー指摘: 上限が無いと
 * `size`等に極端な値を渡すリクエストを弾けない）。`jakarta.validation.Validator`を直接使い、
 * Spring MVCの`@Valid`バインディングを介さず制約アノテーション自体の正しさを検証する。
 */
class BoundsValidationTest {
    private val validator = Validation.buildDefaultValidatorFactory().validator

    @Test
    fun `PromptSearchQueryParams-page-sizeは既定値だと違反なし`() {
        validator.validate(PromptSearchQueryParams()).shouldBeEmpty()
    }

    @Test
    fun `PromptSearchQueryParams-pageが負ならviolation`() {
        validator.validate(PromptSearchQueryParams(page = -1)).size shouldBe 1
    }

    @Test
    fun `PromptSearchQueryParams-sizeが0以下ならviolation`() {
        validator.validate(PromptSearchQueryParams(size = 0)).size shouldBe 1
    }

    @Test
    fun `PromptSearchQueryParams-sizeが上限100を超えるとviolation`() {
        validator.validate(PromptSearchQueryParams(size = 101)).size shouldBe 1
    }

    @Test
    fun `PromptSearchQueryParams-sizeが上限100ちょうどなら違反なし`() {
        validator.validate(PromptSearchQueryParams(size = 100)).shouldBeEmpty()
    }

    @Test
    fun `AuditLogQueryParams-sizeが上限100を超えるとviolation`() {
        val params = AuditLogQueryParams(from = Instant.EPOCH, to = Instant.EPOCH, size = 1_000_000)
        validator.validate(params).size shouldBe 1
    }

    @Test
    fun `OptionsDto-tokenBudgetが上限を超えるとviolation`() {
        validator.validate(OptionsDto(tokenBudget = 10_000_000)).size shouldBe 1
    }

    @Test
    fun `OptionsDto-tokenBudgetが0以下ならviolation`() {
        validator.validate(OptionsDto(tokenBudget = 0)).size shouldBe 1
    }

    @Test
    fun `OptionsDto-tokenBudgetが未指定なら違反なし`() {
        validator.validate(OptionsDto()).shouldBeEmpty()
    }

    @Test
    fun `ExecutionPolicyDto-timeoutMsが上限を超えるとviolation`() {
        validator.validate(ExecutionPolicyDto(timeoutMs = 1_000_000)).size shouldBe 1
    }

    @Test
    fun `ExecutionPolicyDto-maxRetriesが上限10を超えるとviolation`() {
        validator.validate(ExecutionPolicyDto(timeoutMs = 5_000, maxRetries = 11)).size shouldBe 1
    }

    @Test
    fun `ExecutionPolicyDto-妥当な値なら違反なし`() {
        validator.validate(ExecutionPolicyDto(timeoutMs = 5_000, maxRetries = 3)).shouldBeEmpty()
    }

    @Test
    fun `ExecuteRequestDto-executionPolicyのtimeoutMs違反はカスケードして検出される`() {
        val dto =
            ExecuteRequestDto(
                modelProfile = "gpt-class-large",
                executionPolicy = ExecutionPolicyDto(timeoutMs = 1_000_000),
            )
        validator.validate(dto).size shouldBe 1
    }

    @Test
    fun `RenderRequestDto-optionsのtokenBudget違反はカスケードして検出される`() {
        val dto = RenderRequestDto(modelProfile = "gpt-class-large", options = OptionsDto(tokenBudget = -1))
        validator.validate(dto).size shouldBe 1
    }
}
