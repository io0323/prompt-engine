package promptengine.bootstrap

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import io.kotest.matchers.collections.shouldNotBeEmpty
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Configuration
import java.io.File

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
 *
 * 注意（P3での配線が必須なことについて）:
 * `importedClasses` は本モジュール（prompt-engine-bootstrap）のtestランタイムクラスパスを
 * スキャンする。`plugins/` 配下のサブプロジェクトは `settings.gradle.kts` によりGradleプロジェクト
 * としては認識されるが、それだけでは本テストの対象クラスパスには乗らない。P3で最初の
 * Plugin実装を追加する際は、`modules/prompt-engine-bootstrap/build.gradle.kts` の
 * dependencies に `testImplementation(project(":plugins:<name>"))` を必ず追加すること。
 * 配線を忘れた場合に気づけるよう、末尾の
 * 「plugins配下にサブプロジェクトが存在するなら...」テストがガードする。
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
        val forbiddenModules =
            JavaClass.Predicates.resideInAnyPackage(
                "promptengine.engine..",
                "promptengine.infrastructure..",
                "promptengine.interfaces..",
                "promptengine.bootstrap..",
                "promptengine.pluginapi..",
                "promptengine.testkit..",
            )
        // トランザクション境界の宣言はApplication層の責務（設計書§2.3 Pipeline Orchestrator /
        // Command Handlerの責務にトランザクション境界の制御を含む）であるため、
        // org.springframework.transaction.annotation.. のみを唯一の例外として許可する。
        // それ以外のSpring / Jackson / JPA / SLF4Jへの依存は禁止する。
        val forbiddenFrameworks =
            JavaClass.Predicates.resideInAnyPackage(
                "org.springframework..",
                "com.fasterxml.jackson..",
                "jakarta.persistence..",
                "org.slf4j..",
            ).and(
                DescribedPredicate.not(
                    JavaClass.Predicates.resideInAPackage("org.springframework.transaction.annotation.."),
                ),
            )

        noClasses()
            .that().resideInAPackage("promptengine.application..")
            .should().dependOnClassesThat(forbiddenModules.or(forbiddenFrameworks))
            .allowEmptyShould(true)
            .check(importedClasses)
    }

    @Test
    fun `prompt-engine-core と prompt-engine-infrastructure は application interfaces bootstrap に依存しない`() {
        // domainがcore/infrastructureに依存しないこと（規約3前半、Interfaceを実装する側の関係）は
        // Test1（domainは他モジュールに依存しない）が既に検証しているため、ここでは重複させない。
        // 規約3の「逆方向の依存を作らない」は、Test1（domain→engine/infrastructureの禁止）と
        // 本テスト（engine/infrastructure→application/interfaces/bootstrapの禁止）の組で
        // 過不足なく表現される。engine/infrastructureがdomainに依存すること自体は
        // 実装する側として必須であり禁止対象ではない。
        noClasses()
            .that().resideInAnyPackage("promptengine.engine..", "promptengine.infrastructure..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                "promptengine.application..",
                "promptengine.interfaces..",
                "promptengine.bootstrap..",
            )
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

    /**
     * 上記「Plugin実装は...」テストは importedClasses（bootstrapのtestランタイムクラスパス）
     * を対象にしており、これは plugins/ 配下のサブプロジェクトが bootstrap の build.gradle.kts に
     * testImplementation 等で配線されて初めて promptengine.plugin.. を含む。配線を忘れると
     * 検証対象0件のまま allowEmptyShould(true) で恒久的に素通りしてしまい、規約6が
     * 実質検証されない状態に気づけない。plugins/ 配下に実サブプロジェクトが存在するのに
     * promptengine.plugin.. が0件なら、配線漏れとして検知する。
     */
    @Test
    fun `plugins配下にサブプロジェクトが存在するなら promptengine plugin 配下のクラスが1件以上importされている`() {
        val pluginSubprojectDirs = findPluginSubprojectDirs()
        if (pluginSubprojectDirs.isEmpty()) {
            // P0〜P2: plugins/ 配下にサブプロジェクトが未追加のため、このガード自体が対象外。
            return
        }

        val pluginClasses = importedClasses.that(JavaClass.Predicates.resideInAPackage("promptengine.plugin.."))
        pluginClasses.toList().shouldNotBeEmpty()
    }

    /**
     * settings.gradle.kts を上方探索してリポジトリルートを特定する。
     * このリポジトリが他リポジトリの composite build（includeBuild）に取り込まれた場合、
     * 取り込み先にも settings.gradle.kts があると誤ったルートを解決しうる点に注意。
     */
    private fun findPluginSubprojectDirs(): List<File> {
        val repoRoot =
            generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "settings.gradle.kts").exists() }
                ?: error("settings.gradle.kts が見つからずリポジトリルートを特定できません")
        val pluginsDir = File(repoRoot, "plugins")
        if (!pluginsDir.exists()) return emptyList()
        return pluginsDir.listFiles { file -> file.isDirectory }
            ?.filter { File(it, "build.gradle.kts").exists() || File(it, "build.gradle").exists() }
            ?: emptyList()
    }
}
