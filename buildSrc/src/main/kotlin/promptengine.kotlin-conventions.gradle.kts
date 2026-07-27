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
