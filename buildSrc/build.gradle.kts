plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    // buildSrc はルートの Version Catalog を参照できないため、
    // gradle/libs.versions.toml の kotlin / ktlint-gradle / detekt / spring-boot /
    // spring-dependency-management と値を揃えて手動管理する。
    // 各モジュールの build.gradle.kts 側では、これらのプラグインを明示versionで
    // 再解決しないこと（buildSrc がこの version で既にクラスパス上に提供しているため、
    // 別versionを明示指定すると衝突する）。
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
    implementation("org.jetbrains.kotlin:kotlin-allopen:2.4.10")
    implementation("org.jlleitschuh.gradle:ktlint-gradle:12.1.2")
    implementation("io.gitlab.arturbosch.detekt:detekt-gradle-plugin:1.23.8")
    implementation("org.springframework.boot:spring-boot-gradle-plugin:3.5.16")
    implementation("io.spring.gradle:dependency-management-plugin:1.1.7")
}
