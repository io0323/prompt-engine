plugins {
    id("promptengine.kotlin-conventions")
    id("org.jetbrains.kotlin.plugin.spring")
}

// prompt-engine-interface は prompt-engine-application のみを呼ぶ。
// Repository実装（prompt-engine-infrastructure）に直接触れないこと（CLAUDE.md）。
//
// ルートパッケージ名は promptengine.interfaces（複数形）。
// 設計書§3.1 は promptengine.interface と表記しているが、interface はKotlin/Javaの予約語のため
// 使用できない。詳細は docs/adr/0001-interface-package-naming.md を参照。
//
// org.springframework.boot / io.spring.dependency-management プラグイン自体は適用しない
// （bootJarを持つ実行可能モジュールになるのは prompt-engine-bootstrap のみ、CLAUDE.md。
// prompt-engine-infrastructureと同じ理由でGradleネイティブの platform() を使う）。
dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}"))

    implementation(project(":modules:prompt-engine-application"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.oauth2.resource.server)
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation(libs.springdoc.openapi.webmvc.ui)
    implementation(libs.jackson.module.kotlin)
    // org.springframework.dao.DuplicateKeyException(GlobalExceptionHandler、DB一意制約違反の写像)のみに使う。
    // JDBC実装自体への依存はprompt-engine-bootstrapのみが持つ(CLAUDE.md)。
    implementation("org.springframework:spring-tx")

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.security.test)
}
