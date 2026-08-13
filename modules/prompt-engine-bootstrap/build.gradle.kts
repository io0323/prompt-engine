plugins {
    id("promptengine.spring-boot-conventions")
    id("org.springdoc.openapi-gradle-plugin") version "1.8.0"
}

// prompt-engine-bootstrap は Composition Root。具象クラスのDI結線はこのモジュールの
// Configurationクラスでのみ行う（CLAUDE.md）。Spring Boot 依存（web / actuator / security /
// data-jdbc）を持つのはこのモジュールのみ。
springBoot {
    mainClass.set("promptengine.bootstrap.PromptEngineApplicationKt")
}

// `generateOpenApiDocs`（P9c、Issue #13のcontract.yml前提）: bootRunでアプリを起動し
// `/v3/api-docs.yaml`を取得して`api/openapi.yaml`へ書き出す。`/v3/api-docs`は
// `SecurityConfig`で`permitAll`のためBearerトークン不要だが、DataSource（PostgreSQL）への
// 到達は必要（Flywayマイグレーションが起動時に走るため）。CIでは`contract.yml`が
// postgresサービスコンテナを用意した上でこのタスクを実行する。
openApi {
    apiDocsUrl.set("http://localhost:8080/v3/api-docs.yaml")
    outputDir.set(file("$rootDir/api"))
    outputFileName.set("openapi.yaml")
    waitTimeInSeconds.set(60)
}

// RenderLoadSeeder（P11、README「性能測定」節）は実行中の別プロセス（Dockerコンテナ）と
// docker composeのPostgreSQLを前提とするため、通常の./gradlew test / buildには含めない。
// -DincludeTags=perf を明示的に渡した場合のみ実行対象に含める。
tasks.test {
    useJUnitPlatform {
        if (System.getProperty("includeTags") != "perf") {
            excludeTags("perf")
        }
    }
}

dependencies {
    implementation(project(":modules:prompt-engine-domain"))
    implementation(project(":modules:prompt-engine-application"))
    implementation(project(":modules:prompt-engine-core"))
    implementation(project(":modules:prompt-engine-infrastructure"))
    implementation(project(":modules:prompt-engine-interface"))
    implementation(project(":modules:prompt-engine-plugin-api"))

    // P9c時点で存在する唯一のExecutionAdapter/OutputFormatter/TokenizerPlugin/ValidationRule実装。
    // 本番プロファイルガードはFakeExecutionAdapter自身が持つ（ADR-0015方針をP9cで拡張）。
    implementation(project(":plugins:execution-fake"))
    implementation(project(":plugins:formatter-json"))
    implementation(project(":plugins:tokenizer-approx"))
    implementation(project(":plugins:validator-policy"))

    // execution-openai（M2-1a、ADR-0029）はM2-1cでDI配線した（ExecutionConfig、
    // promptengine.execution.provider）。ArchUnit/ProviderNameContainmentTestのallowlistは
    // ExecutionConfig.kt一箇所に限定している（ADR-0030決定4）。
    implementation(project(":plugins:execution-openai"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.data.jdbc)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.oauth2.resource.server)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.springdoc.openapi.webmvc.ui)
    runtimeOnly(libs.postgresql)

    // OutboxRelayConfigがKafka Producerを構築するために必要（ADR-0025決定9）。
    implementation(libs.kafka.clients)

    // P10c: OtelTracerConfigがSdkTracerProvider/OtlpGrpcSpanExporterを構築するために必要（ADR-0027）。
    implementation(libs.opentelemetry.api)
    implementation(libs.opentelemetry.sdk)
    implementation(libs.opentelemetry.exporter.otlp)
    implementation(libs.micrometer.registry.prometheus)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.security.test)
    testImplementation(libs.archunit.junit5)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
}
