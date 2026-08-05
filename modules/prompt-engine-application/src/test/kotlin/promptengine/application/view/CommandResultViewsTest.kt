package promptengine.application.view

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.application.command.ArchiveResult
import promptengine.application.command.CreatePromptResult
import promptengine.application.command.CreateVersionResult
import promptengine.application.command.DeprecateResult
import promptengine.application.command.PublishResult
import promptengine.application.command.RollbackResult
import promptengine.application.command.SetAliasResult
import promptengine.application.command.UpdatePromptMetadataResult
import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.SemVer

class CommandResultViewsTest {
    private val key = PromptKey("support/faq-answer")
    private val semVer = SemVer(1, 0, 0)

    @Test
    fun `CreatePromptResult-toView はkey-semVerをKeySemVerViewへ変換する`() {
        CreatePromptResult(key, semVer).toView() shouldBe KeySemVerView("support/faq-answer", "1.0.0")
    }

    @Test
    fun `CreateVersionResult-toView はkey-semVerをKeySemVerViewへ変換する`() {
        CreateVersionResult(key, semVer).toView() shouldBe KeySemVerView("support/faq-answer", "1.0.0")
    }

    @Test
    fun `PublishResult-toView はkey-semVerをKeySemVerViewへ変換する`() {
        PublishResult(key, semVer).toView() shouldBe KeySemVerView("support/faq-answer", "1.0.0")
    }

    @Test
    fun `DeprecateResult-toView はkey-semVerをKeySemVerViewへ変換する`() {
        DeprecateResult(key, semVer).toView() shouldBe KeySemVerView("support/faq-answer", "1.0.0")
    }

    @Test
    fun `RollbackResult-toView はkey-targetSemVerをRollbackViewへ変換する`() {
        RollbackResult(key, semVer).toView() shouldBe RollbackView("support/faq-answer", "1.0.0")
    }

    @Test
    fun `ArchiveResult-toView はkey-semVer-被参照数をArchiveViewへ変換する`() {
        ArchiveResult(key, semVer, 3).toView() shouldBe ArchiveView("support/faq-answer", "1.0.0", 3)
    }

    @Test
    fun `SetAliasResult-toView はkey-alias-semVerをSetAliasViewへ変換する`() {
        SetAliasResult(key, "stable", semVer).toView() shouldBe SetAliasView("support/faq-answer", "stable", "1.0.0")
    }

    @Test
    fun `UpdatePromptMetadataResult-toView はkeyをKeyOnlyViewへ変換する`() {
        UpdatePromptMetadataResult(key).toView() shouldBe KeyOnlyView("support/faq-answer")
    }
}
