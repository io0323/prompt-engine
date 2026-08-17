package promptengine.bootstrap

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaMethod
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import io.kotest.matchers.collections.shouldNotBeEmpty
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.RequestMapping
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

    /**
     * ADR-0006/ADR-0008: `Prompt.restore`/`Template.restore`/`Fragment.restore` は
     * `@PersistenceApi`（`@RequiresOptIn`）でゲートされた永続化復元専用API。
     * `promptengine.domain.shared`（アノテーション定義）を含む`domain`配下
     * （各Aggregateの`restore`自体の宣言）と`infrastructure.persistence`
     * （唯一の許可された呼び出し元）以外のクラスが`@OptIn(PersistenceApi::class)`等で
     * `PersistenceApi`に依存していないことを検証する。`@RequiresOptIn`自体も
     * コンパイル時に強制するため、本ルールは二重の安全網。
     *
     * ADR-0008でPrompt専用パッケージ（`domain.prompt`）から`domain.shared`へ
     * マーカーを移動し、Template/Fragmentからも参照できるようにした際、許可対象を
     * `domain.prompt`だけでなく`domain..`全体に広げた（restoreはどのAggregateの
     * パッケージに置かれてもよいため）。
     */
    @Test
    fun `PersistenceApiへの依存は domain と infrastructure persistence に限定される`() {
        noClasses()
            .that().resideOutsideOfPackages(
                "promptengine.domain..",
                "promptengine.infrastructure.persistence..",
            )
            .should().dependOnClassesThat()
            .haveFullyQualifiedName("promptengine.domain.shared.PersistenceApi")
            .allowEmptyShould(true)
            .check(importedClasses)
    }

    /**
     * ADR-0009: `ExtendsRef`（extendsのkey+range）の直接構築は、宣言箇所自体
     * （`promptengine.domain.template.ExtendsRef`、コンストラクタへのアノテーション付与も
     * 依存とみなされる）と、DSLソースから実際に導出する2箇所
     * （`promptengine.engine.compiler`のExtendsFieldMapper、
     * `promptengine.infrastructure.persistence`のDB復元経路）以外から行えないことを検証する。
     * `content.source`と無関係な値を任意に構築できてしまうと、「保存された参照 == DSLソースを
     * パースした結果」という整合性がドメイン層のみでは保証できない（domainはパーサに
     * 依存できないため）ことに対する構造的な歯止め。`@PersistenceApi`と同じ、
     * `@RequiresOptIn`によるモジュール境界非依存の強制 + ArchUnitでの二重の安全網。
     *
     * `domain..`全体ではなく`domain.template..`のみを許可対象とすることで、
     * 他のAggregate（`domain.prompt`等）がextendsを独自に導出しようとする経路が
     * 新設されたら検知できるようにする（`PersistenceApi`のルールより意図的に狭い）。
     */
    @Test
    fun `ExtendsRefApiへの依存は domain template と engine compiler と infrastructure persistence に限定される`() {
        noClasses()
            .that().resideOutsideOfPackages(
                "promptengine.domain.template..",
                "promptengine.engine.compiler..",
                "promptengine.infrastructure.persistence..",
            )
            .should().dependOnClassesThat()
            .haveFullyQualifiedName("promptengine.domain.shared.ExtendsRefApi")
            .allowEmptyShould(true)
            .check(importedClasses)
    }

    /**
     * ADR-0034: `ExperimentResolvedVersion.of`（ADR-0024の状態ゲートを迂回する経路）の呼出元を
     * `ExperimentVariantResolver`（`promptengine.application.experiment`）のみに制限する。
     * `PersistenceApi`/`ExtendsRefApi`と同じ、`@RequiresOptIn` + ArchUnitの二重防御。
     */
    @Test
    fun `ExperimentResolutionApiへの依存は domain pipeline と application experiment に限定される`() {
        noClasses()
            .that().resideOutsideOfPackages(
                "promptengine.domain.pipeline..",
                "promptengine.application.experiment..",
            )
            .should().dependOnClassesThat()
            .haveFullyQualifiedName("promptengine.domain.pipeline.ExperimentResolutionApi")
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

    /**
     * 上のテストは「`@Configuration`クラスは`bootstrap`配下にある」しか検証しておらず、
     * 「`@Component`/`@Service`/`@Repository`によるコンポーネントスキャン自己登録が
     * 存在しない」ことは検証していなかった（ADR-0025 CodeRabbitレビューで発覚:
     * `OutboxRelayScheduler`が`@Component`+`@Profile`で自己登録しており、`bootstrap`配下
     * （検証対象の`@Configuration`ではない）のクラスだったため上のテストをすり抜けていた）。
     * 本テストがその欠落を埋める: `promptengine.interfaces..`（`@RestController`/
     * `@RestControllerAdvice`等、Spring MVCの標準的な自己登録）を除き、`@Component`で
     * メタ注釈された具象クラス（`@Configuration`自身・`@SpringBootApplication`も
     * `@Component`のメタ注釈を持つため、それらは別途除外する）が存在しないことを検証する。
     * CLAUDE.mdが指す「具象クラスのDI結線」は、本番/テスト実装の選択（`InMemory`/`Jdbc`/
     * `Kafka`実装の切替等）を伴う実装クラスを指し、Controller層にはそのような「切替候補」が
     * 存在しないため対象外とする（Controllerの自己登録自体は禁止しない）。
     */
    @Test
    fun `interfaces以外の具象クラスはComponent Service Repositoryで自己登録せずbootstrapのBean定義で結線する`() {
        noClasses()
            .that().resideOutsideOfPackages("promptengine.interfaces..")
            .and().areNotMetaAnnotatedWith(Configuration::class.java)
            .should().beMetaAnnotatedWith(Component::class.java)
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
        // で確定。P5（validator-policy、ADR-0012）で最初のPlugin実装が追加され、本テストの
        // testImplementation(project(":plugins:validator-policy"))（bootstrap build.gradle.kts）
        // 配線により検証対象が1件以上になったため、allowEmptyShould(true)を外し実効化した。
        noClasses()
            .that().resideInAPackage("promptengine.plugin..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                "promptengine.application..",
                "promptengine.interfaces..",
                "promptengine.infrastructure..",
                "promptengine.engine..",
                "promptengine.bootstrap..",
                "promptengine.testkit..",
            )
            .check(importedClasses)
    }

    /**
     * Issue #115: `@PreAuthorize`の付け忘れを機械的に検知する。
     *
     * PR #114で発覚した`AuthorizationDeniedException`の500問題は、認可テストを書いて
     * 初めて見つかった。しかしControllerごとの200/403/401テスト（`*AuthorizationTest`）は
     * 「今あるエンドポイント」しか守らない。次に追加されるエンドポイントが
     * `@PreAuthorize`無しでマージされても、既存のテストはどれも落ちない。
     *
     * `promptengine.interfaces.rest`配下の全メソッドのうち、Spring MVCのマッピング
     * アノテーション（`@GetMapping`等）を持つものは、`@PreAuthorize`も併せ持つことを
     * 強制する。`@GetMapping`/`@PostMapping`/`@PatchMapping`/`@DeleteMapping`/`@PutMapping`を
     * 個別に列挙せず、`AnnotatedElementUtils.hasAnnotation(method, RequestMapping::class.java)`
     * （Springのメタアノテーション解決）で判定する。これらのアノテーションはいずれも
     * `@RequestMapping`をメタ注釈として持つため、将来別のマッピングアノテーションが
     * 増えても列挙し直さずに追随できる。
     *
     * 意図的に認可不要なハンドラがある場合は[explicitlyPublicHandlers]へ理由付きで
     * 追加すること。現時点では該当なし（actuatorのヘルスチェック等はこのパッケージの外＝
     * Spring Bootの自動設定エンドポイントであり、そもそも対象に含まれない）。
     */
    @Test
    fun `interfaces rest の全ハンドラメソッドはPreAuthorizeを持つ`() {
        methods()
            .that(IS_REST_MAPPING_HANDLER)
            .should(HAVE_PRE_AUTHORIZE)
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

    private companion object {
        /** 意図的に認可不要と判断したハンドラの許可リスト（"ClassName#methodName"）。現時点では空。 */
        val explicitlyPublicHandlers: Set<String> = emptySet()

        val IS_REST_MAPPING_HANDLER =
            object : DescribedPredicate<JavaMethod>(
                "promptengine.interfaces.rest配下でSpring MVCのマッピングアノテーションを持つ",
            ) {
                override fun test(method: JavaMethod): Boolean =
                    method.owner.packageName.startsWith("promptengine.interfaces.rest") &&
                        AnnotatedElementUtils.hasAnnotation(method.reflect(), RequestMapping::class.java)
            }

        val HAVE_PRE_AUTHORIZE =
            object : ArchCondition<JavaMethod>("@PreAuthorizeを持つ（許可リストの場合を除く）") {
                override fun check(
                    method: JavaMethod,
                    events: ConditionEvents,
                ) {
                    val handlerId = "${method.owner.simpleName}#${method.name}"
                    if (handlerId in explicitlyPublicHandlers) return
                    val satisfied = method.reflect().isAnnotationPresent(PreAuthorize::class.java)
                    val message =
                        "${method.fullName} は@PreAuthorizeを持たない" +
                            "（意図的に認可不要ならexplicitlyPublicHandlersへ理由付きで追加すること）"
                    events.add(SimpleConditionEvent(method, satisfied, message))
                }
            }
    }
}
