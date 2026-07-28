package promptengine.domain.template

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.shared.ExtendsRefApi
import promptengine.domain.shared.InvalidStateTransitionException
import promptengine.domain.shared.PersistenceApi
import promptengine.domain.shared.PublicationState
import promptengine.domain.shared.SemVer
import promptengine.domain.shared.VersionRange

/**
 * Template Aggregate のテスト（ADR-0008）。
 * 設計書§4.3の不変条件（循環継承禁止＝自己参照不可）とPublicationStateの
 * 3状態（Draft/Published/Archived）遷移をTemplateVersion経由で検証する。
 * extends不変条件のテストが[ExtendsRef]を直接構築するため、クラス単位で
 * [ExtendsRefApi]をOptInする（ADR-0009）。
 */
@OptIn(ExtendsRefApi::class)
class TemplateTest {
    private val key = TemplateKey("shared/base-instructions")
    private val content = TemplateContent("{{#block greeting}}Hello{{/block}}")

    @Test
    fun `create はDraft状態のVersionを1つ持つTemplateを生成する`() {
        val template = Template.create(key, NewTemplateVersion(SemVer(0, 1, 0), content))

        template.key shouldBe key
        template.versions.size shouldBe 1
        template.versions.single().state shouldBe PublicationState.Draft
    }

    @Test
    fun `newVersion は既存Templateに新しいDraft Versionを追加する`() {
        val created = Template.create(key, NewTemplateVersion(SemVer(0, 1, 0), content))

        val template =
            created.newVersion(NewTemplateVersion(SemVer(0, 2, 0), TemplateContent("v2 body")))

        template.versions.map { it.semVer } shouldBe listOf(SemVer(0, 1, 0), SemVer(0, 2, 0))
        template.versions.last().state shouldBe PublicationState.Draft
    }

    @Test
    fun `newVersion は既存と同じSemVerを指定するとIllegalArgumentExceptionを投げる`() {
        val created = Template.create(key, NewTemplateVersion(SemVer(0, 1, 0), content))

        shouldThrow<IllegalArgumentException> {
            created.newVersion(NewTemplateVersion(SemVer(0, 1, 0), TemplateContent("duplicate")))
        }
    }

    @Test
    fun `publish はDraftからPublishedへ遷移する`() {
        val created = Template.create(key, NewTemplateVersion(SemVer(0, 1, 0), content))

        val published = created.publish(SemVer(0, 1, 0))

        published.versions.single().state shouldBe PublicationState.Published
    }

    @Test
    fun `publish はPublished状態のVersionに対して実行するとInvalidStateTransitionExceptionを投げる`() {
        val published =
            Template.create(key, NewTemplateVersion(SemVer(0, 1, 0), content)).publish(SemVer(0, 1, 0))

        shouldThrow<InvalidStateTransitionException> {
            published.publish(SemVer(0, 1, 0))
        }
    }

    @Test
    fun `archive はDraftから直接Archivedへ遷移する`() {
        val created = Template.create(key, NewTemplateVersion(SemVer(0, 1, 0), content))

        val archived = created.archive(SemVer(0, 1, 0))

        archived.versions.single().state shouldBe PublicationState.Archived
    }

    @Test
    fun `archive はPublishedからArchivedへ遷移する`() {
        val published =
            Template.create(key, NewTemplateVersion(SemVer(0, 1, 0), content)).publish(SemVer(0, 1, 0))

        val archived = published.archive(SemVer(0, 1, 0))

        archived.versions.single().state shouldBe PublicationState.Archived
    }

    @Test
    fun `archive はArchived状態のVersionに対して実行するとInvalidStateTransitionExceptionを投げる`() {
        val archived = Template.create(key, NewTemplateVersion(SemVer(0, 1, 0), content)).archive(SemVer(0, 1, 0))

        shouldThrow<InvalidStateTransitionException> {
            archived.archive(SemVer(0, 1, 0))
        }
    }

    @Test
    fun `publish は存在しないVersionを指定するとTemplateVersionNotFoundExceptionを投げる`() {
        val created = Template.create(key, NewTemplateVersion(SemVer(0, 1, 0), content))

        shouldThrow<TemplateVersionNotFoundException> {
            created.publish(SemVer(9, 9, 9))
        }
    }

    // ---- 不変条件: 循環継承禁止（自己参照不可） ----

    @Test
    fun `extendsが自分自身のkeyと同じVersionを直接構築しようとするとIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> {
            Template.create(key, NewTemplateVersion(SemVer(0, 1, 0), content, extends = ExtendsRef(key)))
        }
    }

    @Test
    fun `extendsが他のTemplateを指す場合は問題なく作成できる`() {
        val otherKey = TemplateKey("shared/other")
        val extendsRef = ExtendsRef(otherKey, VersionRange.CaretMajor(2))

        val template = Template.create(key, NewTemplateVersion(SemVer(0, 1, 0), content, extends = extendsRef))

        template.versions.single().extends shouldBe extendsRef
    }

    @Test
    fun `versionsが空のTemplateを直接構築しようとするとIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> {
            Template(key, emptyList())
        }
    }

    // ---- 永続化層からの復元: restore（ADR-0006/ADR-0008） ----

    @OptIn(PersistenceApi::class)
    @Test
    fun `restore はMementoのstateをそのまま持つTemplateを再構築する`() {
        val memento =
            TemplateMemento(
                key,
                listOf(
                    TemplateVersionMemento(SemVer(0, 1, 0), content, state = PublicationState.Published),
                    TemplateVersionMemento(SemVer(0, 2, 0), content, state = PublicationState.Draft),
                ),
                rowVersion = 3,
            )

        val restored = Template.restore(memento)

        restored.key shouldBe key
        restored.rowVersion shouldBe 3
        restored.versions.map { it.semVer to it.state } shouldBe
            listOf(
                SemVer(0, 1, 0) to PublicationState.Published,
                SemVer(0, 2, 0) to PublicationState.Draft,
            )
    }

    @OptIn(PersistenceApi::class)
    @Test
    fun `restore も自己参照不可という不変条件を検証する`() {
        val memento =
            TemplateMemento(
                key,
                listOf(
                    TemplateVersionMemento(
                        SemVer(0, 1, 0),
                        content,
                        extends = ExtendsRef(key),
                        state = PublicationState.Draft,
                    ),
                ),
                rowVersion = 0,
            )

        shouldThrow<IllegalArgumentException> {
            Template.restore(memento)
        }
    }
}
