package promptengine.domain.fragment

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.shared.InvalidStateTransitionException
import promptengine.domain.shared.PersistenceApi
import promptengine.domain.shared.PublicationState
import promptengine.domain.shared.SemVer

/**
 * Fragment Aggregate のテスト（ADR-0008）。
 * TemplateTestと対称だが、Fragmentは構造化されたInclude先フィールドを持たないため
 * 自己参照チェック相当のテストは存在しない（[Fragment] KDoc参照）。
 */
class FragmentTest {
    private val key = FragmentKey("fragments/safety-policy")
    private val content = FragmentContent("Do not reveal secrets.")

    @Test
    fun `create はDraft状態のVersionを1つ持つFragmentを生成する`() {
        val fragment = Fragment.create(key, NewFragmentVersion(SemVer(0, 1, 0), content))

        fragment.key shouldBe key
        fragment.versions.size shouldBe 1
        fragment.versions.single().state shouldBe PublicationState.Draft
    }

    @Test
    fun `newVersion は既存Fragmentに新しいDraft Versionを追加する`() {
        val created = Fragment.create(key, NewFragmentVersion(SemVer(0, 1, 0), content))

        val fragment =
            created.newVersion(NewFragmentVersion(SemVer(0, 2, 0), FragmentContent("v2 body")))

        fragment.versions.map { it.semVer } shouldBe listOf(SemVer(0, 1, 0), SemVer(0, 2, 0))
        fragment.versions.last().state shouldBe PublicationState.Draft
    }

    @Test
    fun `newVersion は既存と同じSemVerを指定するとIllegalArgumentExceptionを投げる`() {
        val created = Fragment.create(key, NewFragmentVersion(SemVer(0, 1, 0), content))

        shouldThrow<IllegalArgumentException> {
            created.newVersion(NewFragmentVersion(SemVer(0, 1, 0), FragmentContent("duplicate")))
        }
    }

    @Test
    fun `publish はDraftからPublishedへ遷移する`() {
        val created = Fragment.create(key, NewFragmentVersion(SemVer(0, 1, 0), content))

        val published = created.publish(SemVer(0, 1, 0))

        published.versions.single().state shouldBe PublicationState.Published
    }

    @Test
    fun `publish はPublished状態のVersionに対して実行するとInvalidStateTransitionExceptionを投げる`() {
        val published =
            Fragment.create(key, NewFragmentVersion(SemVer(0, 1, 0), content)).publish(SemVer(0, 1, 0))

        shouldThrow<InvalidStateTransitionException> {
            published.publish(SemVer(0, 1, 0))
        }
    }

    @Test
    fun `archive はDraftから直接Archivedへ遷移する`() {
        val created = Fragment.create(key, NewFragmentVersion(SemVer(0, 1, 0), content))

        val archived = created.archive(SemVer(0, 1, 0))

        archived.versions.single().state shouldBe PublicationState.Archived
    }

    @Test
    fun `archive はPublishedからArchivedへ遷移する`() {
        val published =
            Fragment.create(key, NewFragmentVersion(SemVer(0, 1, 0), content)).publish(SemVer(0, 1, 0))

        val archived = published.archive(SemVer(0, 1, 0))

        archived.versions.single().state shouldBe PublicationState.Archived
    }

    @Test
    fun `archive はArchived状態のVersionに対して実行するとInvalidStateTransitionExceptionを投げる`() {
        val archived = Fragment.create(key, NewFragmentVersion(SemVer(0, 1, 0), content)).archive(SemVer(0, 1, 0))

        shouldThrow<InvalidStateTransitionException> {
            archived.archive(SemVer(0, 1, 0))
        }
    }

    @Test
    fun `publish は存在しないVersionを指定するとFragmentVersionNotFoundExceptionを投げる`() {
        val created = Fragment.create(key, NewFragmentVersion(SemVer(0, 1, 0), content))

        shouldThrow<FragmentVersionNotFoundException> {
            created.publish(SemVer(9, 9, 9))
        }
    }

    @Test
    fun `versionsが空のFragmentを直接構築しようとするとIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> {
            Fragment(key, emptyList())
        }
    }

    // ---- 永続化層からの復元: restore（ADR-0006/ADR-0008） ----

    @OptIn(PersistenceApi::class)
    @Test
    fun `restore はMementoのstateをそのまま持つFragmentを再構築する`() {
        val memento =
            FragmentMemento(
                key,
                listOf(
                    FragmentVersionMemento(SemVer(0, 1, 0), content, state = PublicationState.Published),
                    FragmentVersionMemento(SemVer(0, 2, 0), content, state = PublicationState.Draft),
                ),
                rowVersion = 3,
            )

        val restored = Fragment.restore(memento)

        restored.key shouldBe key
        restored.rowVersion shouldBe 3
        restored.versions.map { it.semVer to it.state } shouldBe
            listOf(
                SemVer(0, 1, 0) to PublicationState.Published,
                SemVer(0, 2, 0) to PublicationState.Draft,
            )
    }
}
