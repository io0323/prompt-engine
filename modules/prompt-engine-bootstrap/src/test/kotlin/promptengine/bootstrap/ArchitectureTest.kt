package promptengine.bootstrap

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Configuration

/**
 * CLAUDE.md「モジュール依存の絶対規約」6項目をルール化する。
 *
 * P0時点では各モジュールにドメインクラスが存在せず、ArchUnitのデフォルト設定
 * （archRule.failOnEmptyShould=true）では検証対象0件のルールは失敗扱いになる。
 * そのため各ルールに allowEmptyShould(true) を付与している。P1以降で実クラスが
 * 追加された時点から、これらのルールが実効的な検証として機能し始める。
 *
 * 注意（プラグイン実装の検証について）:
 * CLAUDE.mdの規約6「Plugin実装は prompt-engine-plugin-api と prompt-engine-domain の
 * 公開型のみを参照する」が指す「Plugin実装」は、plugins ディレクトリ配下（tokenizer-approx 等、
 * P3以降で追加されるGradleサブプロジェクト）のコードを指す。そのパッケージ命名は
 * docs/adr/0003-plugin-package-naming.md で `promptengine.plugin.<category>.<name>` と
 * 確定した。本クラスの `Plugin実装（promptengine.plugin..）は...` テストが規約6の検証に
 * あたる。P0時点では plugins ディレクトリ配下にサブプロジェクトが存在せず検証対象が
 * 0件のため allowEmptyShould(true) を付与しているが、P3で最初のPlugin実装が追加された
 * 時点から実効的な検証として機能し始める。
 * 「公開型のみ」の粒度（パッケージではなく型の可視性）はArchUnitではなくKotlinの
 * internal可視性が担保する。役割分担の詳細はADR-0003を参照。
 * 直下の「plugin-api モジュール自体の依存」テストは、`prompt-engine-plugin-api` モジュール
 * 自身のソースが他レイヤに依存していないかを見る別の検証であり、規約6の検証はあくまで
 * 上記の `promptengine.plugin..` 向けテストが担う。
 */
class ArchitectureTest {
    private val importedClasses =
        ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("promptengine")

    @Test
    fun `prompt-engine-domain は他のいかなるモジュール・フレームワークにも依存しない`() {
        noClasses()
            .that().resideInAPackage("promptengine.domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                "promptengine.application..",
                "promptengine.engine..",
                "promptengine.infrastructure..",
                "promptengine.interfaces..",
                "promptengine.bootstrap..",
                "promptengine.pluginapi..",
                "promptengine.testkit..",
                "org.springframework..",
                "com.fasterxml.jackson..",
                "jakarta.persistence..",
                "javax.persistence..",
                "org.slf4j..",
            )
            .allowEmptyShould(true)
            .check(importedClasses)
    }

    @Test
    fun `prompt-engine-application は prompt-engine-domain のみに依存する`() {
        noClasses()
            .that().resideInAPackage("promptengine.application..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                "promptengine.engine..",
                "promptengine.infrastructure..",
                "promptengine.interfaces..",
                "promptengine.bootstrap..",
                "promptengine.pluginapi..",
                "promptengine.testkit..",
            )
            .allowEmptyShould(true)
            .check(importedClasses)
    }

    @Test
    fun `prompt-engine-core と prompt-engine-infrastructure は domain のInterfaceを実装する側であり逆方向の依存を作らない`() {
        noClasses()
            .that().resideInAPackage("promptengine.domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("promptengine.engine..", "promptengine.infrastructure..")
            .allowEmptyShould(true)
            .check(importedClasses)
    }

    @Test
    fun `prompt-engine-interface は prompt-engine-application のみを呼びRepository実装に直接触れない`() {
        noClasses()
            .that().resideInAPackage("promptengine.interfaces..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                "promptengine.infrastructure..",
                "promptengine.engine..",
                "promptengine.domain..",
                "promptengine.pluginapi..",
                "promptengine.testkit..",
                "promptengine.bootstrap..",
            )
            .allowEmptyShould(true)
            .check(importedClasses)
    }

    @Test
    fun `具象クラスのDI結線は prompt-engine-bootstrap のConfigurationクラスでのみ行う`() {
        classes()
            .that().areAnnotatedWith(Configuration::class.java)
            .should().resideInAPackage("promptengine.bootstrap..")
            .allowEmptyShould(true)
            .check(importedClasses)
    }

    @Test
    fun `prompt-engine-plugin-api モジュール自体は domain 以外のモジュールに依存しない`() {
        noClasses()
            .that().resideInAPackage("promptengine.pluginapi..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                "promptengine.application..",
                "promptengine.engine..",
                "promptengine.infrastructure..",
                "promptengine.interfaces..",
                "promptengine.bootstrap..",
            )
            .allowEmptyShould(true)
            .check(importedClasses)
    }

    @Test
    fun `Plugin実装は promptengine plugin-api と domain 以外のモジュールに依存しない`() {
        // パッケージ命名（promptengine.plugin.カテゴリ.名前）は docs/adr/0003-plugin-package-naming.md
        // で確定。P0時点では plugins ディレクトリ配下にサブプロジェクトが存在せず検証対象0件のため
        // allowEmptyShould(true)。P3で最初のPlugin実装が追加された時点から実効化される。
        noClasses()
            .that().resideInAPackage("promptengine.plugin..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                "promptengine.application..",
                "promptengine.interfaces..",
                "promptengine.infrastructure..",
                "promptengine.engine..",
                "promptengine.bootstrap..",
            )
            .allowEmptyShould(true)
            .check(importedClasses)
    }
}
