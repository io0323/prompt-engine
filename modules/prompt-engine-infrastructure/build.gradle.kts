plugins {
    id("promptengine.kotlin-conventions")
    id("org.jetbrains.kotlin.plugin.spring")
}

// prompt-engine-infrastructure は prompt-engine-domain が定義した Interface を
// 実装する側。逆方向の依存（domain → infrastructure）を作らないこと（CLAUDE.md）。
//
// org.springframework.boot / io.spring.dependency-management プラグイン自体は適用しない
// （bootJarを持つ実行可能モジュールになるのは prompt-engine-bootstrap のみ、CLAUDE.md。
// また io.spring.dependency-management はプロジェクトの全Configuration
// （detekt自身のツールClasspathを含む）にBOMのバージョン制約を及ぼしてしまい、
// detektが期待するKotlinバージョンと衝突する）。spring-boot-starter-* 等のバージョンは
// Gradleネイティブの platform()（implementation configurationのみに適用され、
// detekt等の無関係なConfigurationを汚染しない）でBOMをimportして揃える。
dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}"))

    implementation(project(":modules:prompt-engine-domain"))

    implementation(libs.spring.boot.starter.data.jdbc)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.jackson.module.kotlin)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
}
