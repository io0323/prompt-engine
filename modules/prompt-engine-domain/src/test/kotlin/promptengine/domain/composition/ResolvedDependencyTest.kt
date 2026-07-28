package promptengine.domain.composition

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.fragment.FragmentKey
import promptengine.domain.shared.PublicationState
import promptengine.domain.shared.SemVer
import promptengine.domain.shared.VersionRange
import promptengine.domain.template.TemplateKey

class ResolvedDependencyTest {
    @Test
    fun `TemplateDependency は要求範囲と解決済みVersionの両方を保持する`() {
        val dependency =
            ResolvedDependency.TemplateDependency(
                key = TemplateKey("templates/base-assistant"),
                requestedRange = VersionRange.CaretMajor(2),
                resolvedVersion = SemVer(2, 3, 0),
                status = PublicationState.Published,
                contentHash = "abc123",
            )

        dependency.key shouldBe TemplateKey("templates/base-assistant")
        dependency.requestedRange shouldBe VersionRange.CaretMajor(2)
        dependency.resolvedVersion shouldBe SemVer(2, 3, 0)
        dependency.status shouldBe PublicationState.Published
        dependency.contentHash shouldBe "abc123"
    }

    @Test
    fun `FragmentDependency は要求範囲と解決済みVersionの両方を保持する`() {
        val dependency =
            ResolvedDependency.FragmentDependency(
                key = FragmentKey("fragments/safety-policy"),
                requestedRange = VersionRange.Exact(SemVer(1, 3, 0)),
                resolvedVersion = SemVer(1, 3, 0),
                status = PublicationState.Draft,
                contentHash = "def456",
            )

        dependency.key shouldBe FragmentKey("fragments/safety-policy")
        dependency.requestedRange shouldBe VersionRange.Exact(SemVer(1, 3, 0))
        dependency.resolvedVersion shouldBe SemVer(1, 3, 0)
        dependency.status shouldBe PublicationState.Draft
    }

    @Test
    fun `同じ内容のTemplateDependencyは等しい`() {
        val a =
            ResolvedDependency.TemplateDependency(
                TemplateKey("templates/base"),
                VersionRange.Latest,
                SemVer(1, 0, 0),
                PublicationState.Published,
                "hash",
            )
        val b =
            ResolvedDependency.TemplateDependency(
                TemplateKey("templates/base"),
                VersionRange.Latest,
                SemVer(1, 0, 0),
                PublicationState.Published,
                "hash",
            )

        a shouldBe b
    }
}
