package promptengine.engine.compiler

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.validation.PlaceholderMode
import promptengine.domain.validation.ValidationSettings

class ValidationFieldMapperTest {
    @Test
    fun `nullなら全項目既定値のValidationSettingsを返す`() {
        ValidationFieldMapper.parse(null) shouldBe ValidationSettings()
    }

    @Test
    fun `maxLength maxTokens policies placeholders を変換する`() {
        val raw =
            mapOf(
                "maxLength" to 32000,
                "maxTokens" to 8000,
                "policies" to listOf("no-pii", "corporate-tone"),
                "placeholders" to "strict",
            )

        val settings = ValidationFieldMapper.parse(raw)

        settings shouldBe
            ValidationSettings(
                maxLength = 32000,
                maxTokens = 8000,
                policies = listOf("no-pii", "corporate-tone"),
                placeholders = PlaceholderMode.STRICT,
            )
    }

    @Test
    fun `placeholdersがlenientならPlaceholderMode LENIENTになる`() {
        ValidationFieldMapper.parse(mapOf("placeholders" to "lenient")).placeholders shouldBe PlaceholderMode.LENIENT
    }

    @Test
    fun `placeholders省略時はLENIENTになる`() {
        ValidationFieldMapper.parse(mapOf("maxLength" to 100)).placeholders shouldBe PlaceholderMode.LENIENT
    }

    @Test
    fun `マッピングでなければIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> { ValidationFieldMapper.parse("not-a-map") }
    }

    @Test
    fun `placeholdersがstrict lenient以外ならIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> { ValidationFieldMapper.parse(mapOf("placeholders" to "loose")) }
    }

    @Test
    fun `maxLengthが数値でなければIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> { ValidationFieldMapper.parse(mapOf("maxLength" to "100")) }
    }

    @Test
    fun `maxLengthが小数ならIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> { ValidationFieldMapper.parse(mapOf("maxLength" to 100.5)) }
    }

    @Test
    fun `maxTokensが数値でなければIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> { ValidationFieldMapper.parse(mapOf("maxTokens" to "8000")) }
    }

    @Test
    fun `policiesがリストでなければIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> { ValidationFieldMapper.parse(mapOf("policies" to "no-pii")) }
    }

    @Test
    fun `policiesの要素が文字列でなければIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> { ValidationFieldMapper.parse(mapOf("policies" to listOf(1, 2))) }
    }
}
