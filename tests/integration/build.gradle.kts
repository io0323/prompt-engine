plugins {
    kotlin("jvm")
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
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

val integrationTest =
    tasks.register<Test>("integrationTest") {
        description = "Testcontainers(PostgreSQL 16)によるInfrastructure層の統合テスト（設計書§6.3）。"
        group = "verification"
        testClassesDirs = sourceSets["integrationTest"].output.classesDirs
        classpath = sourceSets["integrationTest"].runtimeClasspath
        useJUnitPlatform()
    }

dependencies {
    "integrationTestImplementation"(project(":modules:prompt-engine-domain"))
    "integrationTestImplementation"(project(":modules:prompt-engine-infrastructure"))

    "integrationTestImplementation"(
        platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}"),
    )
    "integrationTestImplementation"(libs.spring.boot.starter.data.jdbc)
    "integrationTestImplementation"(libs.flyway.core)
    "integrationTestImplementation"(libs.flyway.postgresql)
    "integrationTestImplementation"(libs.jackson.module.kotlin)
    "integrationTestRuntimeOnly"(libs.postgresql)

    "integrationTestImplementation"(platform(libs.junit.bom))
    "integrationTestImplementation"(libs.junit.jupiter)
    "integrationTestRuntimeOnly"(libs.junit.platform.launcher)
    "integrationTestImplementation"(libs.kotest.assertions.core)

    "integrationTestImplementation"(platform(libs.testcontainers.bom))
    "integrationTestImplementation"(libs.testcontainers.junit.jupiter)
    "integrationTestImplementation"(libs.testcontainers.postgresql)
}
