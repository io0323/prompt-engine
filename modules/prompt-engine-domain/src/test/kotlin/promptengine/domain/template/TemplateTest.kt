package promptengine.domain.template

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.event.EventContext
import promptengine.domain.shared.ExtendsRefApi
import promptengine.domain.shared.InvalidStateTransitionException
import promptengine.domain.shared.PersistenceApi
import promptengine.domain.shared.PublicationState
import promptengine.domain.shared.SemVer
import promptengine.domain.shared.VersionRange
import java.time.Instant

/**
 * Template Aggregate のテスト（ADR-0008、Domain EventはADR-0033）。
 * 設計書§4.3の不変条件（循環継承禁止＝自己参照不可）とPublicationStateの
 * 3状態（Draft/Published/Archived）遷移をTemplateVersion経由で検証する。
 * extends不変条件のテストが[ExtendsRef]を直接構築するため、クラス単位で
 * [ExtendsRefApi]をOptInする（ADR-0009）。
 */
@OptIn(ExtendsRefApi::class)
class TemplateTest {
    private val key = TemplateKey("shared/base-instructions")
    private val content = TemplateContent("{{#block greeting}}Hello{{/block}}")
    private val context =
        EventContext(actor = "tester", traceId = "trace-1", occurredAt = Instant.parse("2026-01-01T00:00:00Z"))

    private fun createTemplate(
        semVer: SemVer = SemVer(0, 1, 0),
        version: NewTemplateVersion = NewTemplateVersion(semVer, content),
    ): Template = Template.create(key, version, context).first

    @Test
    fun `create はDraft状態のVersionを1つ持つTemplateを生成する`() {
        val (template, event) = Template.create(key, NewTemplateVersion(SemVer(0, 1, 0), content), context)

        template.key shouldBe key
        template.versions.size shouldBe 1
        template.versions.single().state shouldBe PublicationState.Draft
        event.aggregateId shouldBe key.value
        event.payload shouldBe TemplateCreated.Payload(key.value, SemVer(0, 1, 0))
    }

    @Test
    fun `newVersion は既存Templateに新しいDraft Versionを追加する`() {
        val created = createTemplate()

        val (template, event) =
            created.newVersion(NewTemplateVersion(SemVer(0, 2, 0), TemplateContent("v2 body")), context)

        template.versions.map { it.semVer } shouldBe listOf(SemVer(0, 1, 0), SemVer(0, 2, 0))
        template.versions.last().state shouldBe PublicationState.Draft
        event.payload shouldBe TemplateVersionCreated.Payload(key.value, SemVer(0, 2, 0))
    }

    @Test
    fun `newVersion は既存と同じSemVerを指定するとIllegalArgumentExceptionを投げる`() {
        val created = createTemplate()

        shouldThrow<IllegalArgumentException> {
            created.newVersion(NewTemplateVersion(SemVer(0, 1, 0), TemplateContent("duplicate")), context)
        }
    }

    @Test
    fun `publish はDraftからPublishedへ遷移する`() {
        val created = createTemplate()

        val (published, event) = created.publish(SemVer(0, 1, 0), context)

        published.versions.single().state shouldBe PublicationState.Published
        event.payload shouldBe TemplatePublished.Payload(key.value, SemVer(0, 1, 0))
    }

    @Test
    fun `publish はPublished状態のVersionに対して実行するとInvalidStateTransitionExceptionを投げる`() {
        val published = createTemplate().publish(SemVer(0, 1, 0), context).first

        shouldThrow<InvalidStateTransitionException> {
            published.publish(SemVer(0, 1, 0), context)
        }
    }

    @Test
    fun `archive はDraftから直接Archivedへ遷移する`() {
        val created = createTemplate()

        val (archived, event) = created.archive(SemVer(0, 1, 0), context)

        archived.versions.single().state shouldBe PublicationState.Archived
        event.payload shouldBe TemplateArchived.Payload(key.value, SemVer(0, 1, 0))
    }

    @Test
    fun `archive はPublishedからArchivedへ遷移する`() {
        val published = createTemplate().publish(SemVer(0, 1, 0), context).first

        val archived = published.archive(SemVer(0, 1, 0), context).first

        archived.versions.single().state shouldBe PublicationState.Archived
    }

    @Test
    fun `archive はArchived状態のVersionに対して実行するとInvalidStateTransitionExceptionを投げる`() {
        val archived = createTemplate().archive(SemVer(0, 1, 0), context).first

        shouldThrow<InvalidStateTransitionException> {
            archived.archive(SemVer(0, 1, 0), context)
        }
    }

    @Test
    fun `publish は存在しないVersionを指定するとTemplateVersionNotFoundExceptionを投げる`() {
        val created = createTemplate()

        shouldThrow<TemplateVersionNotFoundException> {
            created.publish(SemVer(9, 9, 9), context)
        }
    }

    // ---- 不変条件: 循環継承禁止（自己参照不可） ----

    @Test
    fun `extendsが自分自身のkeyと同じVersionを直接構築しようとするとIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> {
            Template.create(key, NewTemplateVersion(SemVer(0, 1, 0), content, extends = ExtendsRef(key)), context)
        }
    }

    @Test
    fun `extendsが他のTemplateを指す場合は問題なく作成できる`() {
        val otherKey = TemplateKey("shared/other")
        val extendsRef = ExtendsRef(otherKey, VersionRange.CaretMajor(2))

        val template = createTemplate(version = NewTemplateVersion(SemVer(0, 1, 0), content, extends = extendsRef))

        template.versions.single().extends shouldBe extendsRef
    }

    @Test
    fun `versionsが空のTemplateを直接構築しようとするとIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> {
            Template(key, emptyList())
        }
    }

    @Test
    fun `newVersion で追加したVersionのextendsは完全な形key+rangeで保持される`() {
        val created = createTemplate()
        val otherKey = TemplateKey("shared/other")
        val extendsRef = ExtendsRef(otherKey, VersionRange.Exact(SemVer(1, 3, 0)))

        val template =
            created
                .newVersion(
                    NewTemplateVersion(SemVer(0, 2, 0), TemplateContent("v2 body"), extends = extendsRef),
                    context,
                ).first

        template.versions.last().extends shouldBe extendsRef
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
    fun `restore は非デフォルトのVersionRangeを持つextendsをそのまま再構築する`() {
        val otherKey = TemplateKey("shared/other")
        val extendsRef = ExtendsRef(otherKey, VersionRange.CaretMajor(2))
        val memento =
            TemplateMemento(
                key,
                listOf(
                    TemplateVersionMemento(
                        SemVer(0, 1, 0),
                        content,
                        extends = extendsRef,
                        state = PublicationState.Published,
                    ),
                ),
                rowVersion = 1,
            )

        val restored = Template.restore(memento)

        restored.versions.single().extends shouldBe extendsRef
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
