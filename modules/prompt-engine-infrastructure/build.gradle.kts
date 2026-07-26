plugins {
    id("promptengine.kotlin-conventions")
    id("org.jetbrains.kotlin.plugin.spring")
    id("io.spring.dependency-management")
}

// prompt-engine-infrastructure は prompt-engine-domain が定義した Interface を
// 実装する側。逆方向の依存（domain → infrastructure）を作らないこと（CLAUDE.md）。
//
// org.springframework.boot プラグイン自体は適用しない（bootJarを持つ実行可能モジュールに
// なるのは prompt-engine-bootstrap のみ、CLAUDE.md）。spring-boot-starter-* の
// バージョンは io.spring.dependency-management による spring-boot-dependencies BOM
// importで揃える。
dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}")
    }
}

dependencies {
    implementation(project(":modules:prompt-engine-domain"))

    implementation(libs.spring.boot.starter.data.jdbc)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.jackson.module.kotlin)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
}
