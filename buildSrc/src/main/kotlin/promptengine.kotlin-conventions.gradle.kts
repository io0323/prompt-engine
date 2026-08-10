import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    kotlin("jvm")
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
    jacoco
}

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

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.named("jacocoTestReport"))
}

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

/**
 * 分岐・行カバレッジの下限（劣化検知用）。既定は0.0（未設定＝検証しない）。
 * 各モジュールのbuild.gradle.ktsで `extra["jacocoMinLineCoverage"]` /
 * `extra["jacocoMinBranchCoverage"]`（Double）を設定したモジュールのみ実効化する
 * （P3aの分岐カバレッジ監査で prompt-engine-domain / prompt-engine-core に設定。
 * 実測値を下回る「切りの良い」値にしてあるため、実測値の上下動に過敏に反応しない）。
 */
val minLineCoverage: Double
    get() = (project.findProperty("jacocoMinLineCoverage") as? Double) ?: 0.0

val minBranchCoverage: Double
    get() = (project.findProperty("jacocoMinBranchCoverage") as? Double) ?: 0.0

tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn(tasks.named("jacocoTestReport"))
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = minLineCoverage.toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                minimum = minBranchCoverage.toBigDecimal()
            }
        }
    }
}

tasks.named("check") {
    dependsOn(tasks.named("jacocoTestCoverageVerification"))
}

/**
 * 別Gradleプロジェクト（`tests/integration`）で実行される統合テストの実行時カバレッジを
 * 取り込んだ「集約レポート」と、その集約値に対する下限検証（P10b、ADR-0026決定8の見直し）。
 *
 * ## なぜ既定の jacocoTestReport / jacocoTestCoverageVerification と分けるのか
 * `prompt-engine-infrastructure` の主要部分（JDBC Repository）は、CLAUDE.mdのテスト規約に
 * 従い`tests/integration`（Testcontainers）で検証している。既定の`jacocoTestReport`は
 * 自プロジェクトの`test`タスクのexecしか読まないため、この層の実測値が常に過少に出る。
 *
 * かといって既定のレポート／検証タスク自体に統合テストのexecを合流させると、それらは
 * `check`（延いては`build`）に配線されているため、**`./gradlew build`だけでDocker
 * （Testcontainers）が必要になる**。これは`tests/integration/build.gradle.kts`冒頭が
 * 明示的に避けている挙動であり、CLAUDE.mdの`test`と`integrationTest`の使い分けにも反する。
 *
 * このため集約は独立したタスク対（`jacocoAggregatedReport` /
 * `jacocoAggregatedCoverageVerification`）として提供し、`check`には配線しない。
 * CIの`test`ジョブが`integrationTest`のあとに明示的に呼ぶ（.github/workflows/ci.yml）。
 *
 * オプトインしたモジュールのみで生成する。有効化するには、モジュールの build.gradle.kts で
 * `extra["jacocoAggregatedExecutionData"]`（execファイルのパスのList<String>）と
 * `extra["jacocoAggregatedTaskDependencies"]`（execを生成するタスクパスのList<String>）、
 * および下限 `extra["jacocoAggregatedMinLineCoverage"]` /
 * `extra["jacocoAggregatedMinBranchCoverage"]` を設定する。
 */
// 設定の読み取りとタスク登録は afterEvaluate で行う。プリコンパイル済みスクリプトプラグインの
// 本体はプラグイン適用時（＝モジュールの build.gradle.kts が extra を設定する前）に実行される
// ため、ここで即座に extra を読むと常に未設定として扱われてしまう。
afterEvaluate {
    @Suppress("UNCHECKED_CAST")
    val aggregatedExecutionData: List<String> =
        (project.findProperty("jacocoAggregatedExecutionData") as? List<String>) ?: emptyList()

    @Suppress("UNCHECKED_CAST")
    val aggregatedTaskDependencies: List<String> =
        (project.findProperty("jacocoAggregatedTaskDependencies") as? List<String>) ?: emptyList()

    val aggregatedMinLineCoverage =
        (project.findProperty("jacocoAggregatedMinLineCoverage") as? Double) ?: 0.0
    val aggregatedMinBranchCoverage =
        (project.findProperty("jacocoAggregatedMinBranchCoverage") as? Double) ?: 0.0

    if (aggregatedExecutionData.isEmpty()) return@afterEvaluate

    val aggregatedSources = files("src/main/kotlin")
    val aggregatedClasses = files(layout.buildDirectory.dir("classes/kotlin/main"))

    val jacocoAggregatedReport =
        tasks.register<JacocoReport>("jacocoAggregatedReport") {
            description = "自プロジェクトのtestに加えtests/integrationの統合テストのカバレッジを合算したレポート。"
            group = "verification"
            dependsOn(tasks.named("test"))
            aggregatedTaskDependencies.forEach { dependsOn(it) }

            // execファイルは対応するテストタスクが実行された場合にのみ存在する。dependsOnで
            // 生成を保証したうえで、存在するものだけを読む（未生成のパスを渡すとJacocoが失敗する）。
            executionData.setFrom(
                files(
                    layout.buildDirectory.file("jacoco/test.exec"),
                    *aggregatedExecutionData.map { rootProject.file(it) }.toTypedArray(),
                ).filter { it.exists() },
            )
            sourceDirectories.setFrom(aggregatedSources)
            classDirectories.setFrom(aggregatedClasses)
            reports {
                xml.required.set(true)
                html.required.set(true)
            }
        }

    tasks.register<JacocoCoverageVerification>("jacocoAggregatedCoverageVerification") {
        description = "集約カバレッジ（単体+統合）が下限を下回らないことを検証する。"
        group = "verification"
        dependsOn(jacocoAggregatedReport)
        executionData.setFrom(jacocoAggregatedReport.map { it.executionData })
        sourceDirectories.setFrom(aggregatedSources)
        classDirectories.setFrom(aggregatedClasses)
        violationRules {
            rule {
                limit {
                    counter = "LINE"
                    minimum = aggregatedMinLineCoverage.toBigDecimal()
                }
                limit {
                    counter = "BRANCH"
                    minimum = aggregatedMinBranchCoverage.toBigDecimal()
                }
            }
        }
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // gradle/libs.versions.toml の junit / kotest / mockk とバージョンを揃えて手動管理する
    // （buildSrc からルートの Version Catalog を参照しないため）。
    "testImplementation"(platform("org.junit:junit-bom:5.11.4"))
    "testImplementation"("org.junit.jupiter:junit-jupiter")
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    "testImplementation"("io.kotest:kotest-runner-junit5:5.9.1")
    "testImplementation"("io.kotest:kotest-assertions-core:5.9.1")
    "testImplementation"("io.mockk:mockk:1.13.13")
}
