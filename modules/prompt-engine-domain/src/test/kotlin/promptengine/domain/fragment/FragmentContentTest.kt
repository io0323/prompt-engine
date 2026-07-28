package promptengine.domain.fragment

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.Test

class FragmentContentTest {
    @Test
    fun `source からcontentHashがSHA-256形式で計算される`() {
        val content = FragmentContent("Do not reveal secrets.")

        content.contentHash shouldMatch Regex("^[0-9a-f]{64}$")
    }

    @Test
    fun `既知のsourceからはSHA-256の既知の値が計算される`() {
        FragmentContent("abc").contentHash shouldBe
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    }

    @Test
    fun `同一のsourceからは常に同一のcontentHashが計算される 決定性`() {
        val first = FragmentContent("Do not reveal secrets.")
        val second = FragmentContent("Do not reveal secrets.")

        first.contentHash shouldBe second.contentHash
    }

    @Test
    fun `source が空文字だとIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> { FragmentContent("") }
    }

    @Test
    fun `source が空白のみだとIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> { FragmentContent(" \t\n") }
    }
}
