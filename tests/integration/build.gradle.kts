import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestResult

// JUnit Platform Gradle XMLレポートのルート要素 `<testsuite ... tests="N" ...>` から
// 実行件数を読み取るための正規表現（`verifyIntegrationTestExecuted`が使用）。
val testsuiteTestsAttribute = Regex("""<testsuite\b[^>]*\stests="(\d+)"""")

plugins {
    kotlin("jvm")
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
    // integrationTaskの実行時カバレッジ（.exec）を採取するためだけに適用する。
    // レポート生成はこのプロジェクトでは行わず、計測対象である
    // prompt-engine-infrastructure 側の集約レポート
    // （jacocoAggregatedReport / jacocoAggregatedCoverageVerification）が読み取る。
    jacoco
}

// promptengine.kotlin-conventions（buildSrc）はあえて適用しない。それが配線する
// 既定の `test` タスクに乗せると、`./gradlew test`/`./gradlew build` の実行だけで
// Testcontainers（Docker）が必要になってしまう（CLAUDE.mdの `./gradlew test` と
// `./gradlew integrationTest` の使い分けに反する）。専用の `integrationTest`
// SourceSet・Taskとして独立させ、既定の `test` タスクにはソースを乗せない。
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    jvmToolchain(21)
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
}

sourceSets {
    create("integrationTest") {
        kotlin.srcDir("src/integrationTest/kotlin")
        resources.srcDir("src/integrationTest/resources")
    }
}

// integrationTest自体がNO-SOURCE/SKIPPEDで丸ごとスキップされた場合、そのdoLast（下記の
// 0件ガード）自体が実行されない。過去の実行結果XMLが残っていると、今回スキップされた
// にもかかわらず前回分のXMLを誤って「実行済み」と読んでしまう恐れがあるため、
// integrationTestの実行前に必ず結果ディレクトリを空にする（verifyIntegrationTestExecutedが
// 読む対象を「今回の実行結果のみ」に限定するため）。
val cleanIntegrationTestResults =
    tasks.register<Delete>("cleanIntegrationTestResults") {
        description = "integrationTestの前回実行結果XMLを削除する（verifyIntegrationTestExecutedの前提整備）。"
        delete(layout.buildDirectory.dir("test-results/integrationTest"))
    }

val integrationTest =
    tasks.register<Test>("integrationTest") {
        description = "Testcontainers(PostgreSQL 16)によるInfrastructure層の統合テスト（設計書§6.3）。"
        group = "verification"
        dependsOn(cleanIntegrationTestResults)
        testClassesDirs = sourceSets["integrationTest"].output.classesDirs
        classpath = sourceSets["integrationTest"].runtimeClasspath
        useJUnitPlatform()

        // 実行時カバレッジを固定パスの.execへ書き出す（prompt-engine-infrastructureの
        // 集約レポートが参照する。既定パスはタスク名依存で分かりにくいため明示する）。
        extensions.configure<JacocoTaskExtension> {
            destinationFile = layout.buildDirectory.file("jacoco/integrationTest.exec").get().asFile
        }

        // SourceSet配線やCIのタスク検出が壊れて対象0件のまま "BUILD SUCCESSFUL" になる
        // (silent green)を防ぐガード。ArchitectureTestの「plugins配下にサブプロジェクトが
        // 存在するなら...」ガードと同じ発想（CLAUDE.md「実装の進め方」参照）。
        //
        // 注意: このガードは本タスク自身が実行された場合（テストは走ったが対象0件）にしか
        // 発火しない。SourceSetの配線が壊れて本タスク自体がNO-SOURCEでスキップされる場合は
        // このdoLast自体が呼ばれず検出できない。そちらは別タスク
        // `verifyIntegrationTestExecuted`（後述）が担当する。
        var executedTestCount = 0L
        addTestListener(
            object : TestListener {
                override fun beforeSuite(suite: TestDescriptor) = Unit

                override fun afterSuite(
                    suite: TestDescriptor,
                    result: TestResult,
                ) {
                    if (suite.parent == null) executedTestCount = result.testCount
                }

                override fun beforeTest(testDescriptor: TestDescriptor) = Unit

                override fun afterTest(
                    testDescriptor: TestDescriptor,
                    result: TestResult,
                ) = Unit
            },
        )
        doLast {
            check(executedTestCount > 0) {
                "integrationTestの実行件数が0件でした。SourceSet配線が壊れているか、" +
                    "テストが検出されていません（silent greenの防止のため失敗させています）。"
            }
        }
    }

/**
 * `integrationTest`タスク自体がSourceSet配線の破損等でNO-SOURCE/SKIPPEDのまま
 * "BUILD SUCCESSFUL" になるsilent greenを検出する（`integrationTest`内のdoLastガードは
 * タスクが実際に実行された場合にしか発火しないため、それを補完する独立したタスク）。
 *
 * `src/integrationTest/kotlin`配下の`*IntegrationTest.kt`ソースファイル数と、
 * `integrationTest`が実際に書き出したJUnit XML結果（`tests`属性の合計）を突き合わせ、
 * ソースが1件以上存在するのに実行件数が0件ならビルドを失敗させる。`dependsOn(integrationTest)`
 * により、`integrationTest`自体がNO-SOURCEでスキップされた場合でも本タスクは実行される
 * （Gradleの`dependsOn`は依存先タスクがスキップされても依存元タスクの実行を妨げない）。
 * `outputs.upToDateWhen { false }`でGradleの再利用判定（UP-TO-DATE化）自体を無効化し、
 * 毎回必ず本文を実行させる。
 */
val verifyIntegrationTestExecuted =
    tasks.register("verifyIntegrationTestExecuted") {
        description = "integrationTestがNO-SOURCE等でsilent greenスキップされていないことを検証する。"
        group = "verification"
        dependsOn(integrationTest)
        outputs.upToDateWhen { false }
        doLast {
            val sourceFiles =
                fileTree("src/integrationTest/kotlin") { include("**/*IntegrationTest.kt") }.files
            val resultsDir = layout.buildDirectory.dir("test-results/integrationTest").get().asFile
            val resultFiles =
                resultsDir.takeIf { it.isDirectory }
                    ?.listFiles { file -> file.isFile && file.name.startsWith("TEST-") && file.extension == "xml" }
                    ?: emptyArray()
            val executedTestCount =
                resultFiles.sumOf { xmlFile ->
                    testsuiteTestsAttribute.find(xmlFile.readText())?.groupValues?.get(1)?.toIntOrNull() ?: 0
                }

            if (sourceFiles.isNotEmpty()) {
                check(executedTestCount > 0) {
                    "src/integrationTest/kotlin に ${sourceFiles.size} 件の *IntegrationTest.kt が" +
                        "存在するのに、integrationTestの実行結果（${resultFiles.size}件のXML、" +
                        "合計${executedTestCount}テスト）が0件でした。integrationTestタスクが" +
                        "NO-SOURCE等でスキップされ、SourceSet配線が壊れている可能性があります" +
                        "（silent greenの防止のため失敗させています）。"
                }
            }
        }
    }

// `check`/`build`（延いては`./gradlew build`）には配線しない。integrationTestと同じ理由
// （ファイル冒頭のコメント参照）で、`./gradlew build`だけでDocker（Testcontainers）が
// 必要になることを避けるため。CIの`test`ジョブから`:tests:integration:verifyIntegrationTestExecuted`
// を明示的に呼ぶ（.github/workflows/ci.yml）。

dependencies {
    "integrationTestImplementation"(project(":modules:prompt-engine-domain"))
    "integrationTestImplementation"(project(":modules:prompt-engine-infrastructure"))
    // M2-3: Fragment publish→キャッシュ無効化を内容で検証する統合テストが、実際の
    // CompositionServiceImpl（prompt-engine-core）・MergeStage（prompt-engine-application）を
    // 使うため（ADR-0033）。
    "integrationTestImplementation"(project(":modules:prompt-engine-core"))
    "integrationTestImplementation"(project(":modules:prompt-engine-application"))
    // ADR-0035フェーズ(c): BenchmarkWorker統合テストが実行アダプタ境界（temperature受け渡し・
    // Cyclingシナリオによる二重実行検証）に実際のFakeExecutionAdapterを使うため。
    "integrationTestImplementation"(project(":plugins:execution-fake"))

    "integrationTestImplementation"(
        platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}"),
    )
    "integrationTestImplementation"(libs.spring.boot.starter.data.jdbc)
    "integrationTestImplementation"(libs.flyway.core)
    "integrationTestImplementation"(libs.flyway.postgresql)
    "integrationTestImplementation"(libs.jackson.module.kotlin)
    "integrationTestRuntimeOnly"(libs.postgresql)

    // M2-3: RedisPromptCache統合テスト（ADR-0033決定d）。
    "integrationTestImplementation"(libs.lettuce.core)

    "integrationTestImplementation"(platform(libs.junit.bom))
    "integrationTestImplementation"(libs.junit.jupiter)
    "integrationTestRuntimeOnly"(libs.junit.platform.launcher)
    "integrationTestImplementation"(libs.kotest.assertions.core)

    "integrationTestImplementation"(platform(libs.testcontainers.bom))
    "integrationTestImplementation"(libs.testcontainers.junit.jupiter)
    "integrationTestImplementation"(libs.testcontainers.postgresql)

    // Redpanda（Kafka互換Broker）統合テスト（ADR-0025決定9）。
    "integrationTestImplementation"(libs.testcontainers.redpanda)
    "integrationTestImplementation"(libs.kafka.clients)
}
