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
}
