plugins {
    id("promptengine.spring-boot-conventions")
}

// prompt-engine-bootstrap は Composition Root。具象クラスのDI結線はこのモジュールの
// Configurationクラスでのみ行う（CLAUDE.md）。Spring Boot 依存（web / actuator）を
// 持つのはこのモジュールのみ。
springBoot {
    mainClass.set("promptengine.bootstrap.PromptEngineApplicationKt")
}

dependencies {
    implementation(project(":modules:prompt-engine-domain"))
    implementation(project(":modules:prompt-engine-application"))
    implementation(project(":modules:prompt-engine-core"))
    implementation(project(":modules:prompt-engine-infrastructure"))
    implementation(project(":modules:prompt-engine-interface"))
    implementation(project(":modules:prompt-engine-plugin-api"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.archunit.junit5)
    // ArchitectureTestの規約6テストが検証対象を持つために必要（plugins/validator-policy、
    // ADR-0003・ADR-0012）。テスト専用であり、本番のDI結線はP8/P9のConfigurationクラスで行う。
    testImplementation(project(":plugins:validator-policy"))
    // 同上（plugins/tokenizer-approx、ADR-0003・ADR-0013）。
    testImplementation(project(":plugins:tokenizer-approx"))
}
