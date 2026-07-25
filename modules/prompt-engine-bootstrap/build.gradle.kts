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
}
