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

    // OutboxRelayerがBrokerへ送信するために使うProducerクライアント（ADR-0025決定9）。
    implementation(libs.kafka.clients)

    // M2-3: RedisPromptCacheが使うRedisクライアント（ADR-0033決定d）。
    implementation(libs.lettuce.core)

    // P10c: MicrometerMetricsRecorder / OpenTelemetryPipelineTracerの実装に使用（ADR-0027）。
    implementation(libs.micrometer.core)
    implementation(libs.opentelemetry.api)
    implementation(libs.opentelemetry.sdk)
    implementation(libs.opentelemetry.exporter.otlp)
    implementation(libs.logback.classic)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.opentelemetry.sdk.testing)
}

// カバレッジ集約（P10b、ADR-0026決定8を見直し）。
//
// このモジュールの主要部分（JDBC Repository）はCLAUDE.mdのテスト規約に従い
// `tests/integration`（Testcontainers）で検証しており、既定の`jacocoTestReport`
// （自プロジェクトの`test`のexecのみ）には現れない。P10bで直近の実バグ
// （Secretマスキングの誤検知）がこの層に集中したことを踏まえ、統合テストの実行時
// カバレッジを合算した集約レポートに対して下限ゲートを設ける。
//
// 既定の jacocoTestCoverageVerification（`check`/`build`に配線済み）ではなく独立した
// `jacocoAggregatedCoverageVerification` に下限を設定するのは、既定タスクへ統合テストの
// execを合流させると `./gradlew build` だけでDockerが必要になるため
// （tests/integration/build.gradle.kts 冒頭が明示的に避けている挙動）。
// CIの`test`ジョブが integrationTest のあとに明示的に呼ぶ。
extra["jacocoAggregatedExecutionData"] =
    listOf("tests/integration/build/jacoco/integrationTest.exec")
extra["jacocoAggregatedTaskDependencies"] = listOf(":tests:integration:integrationTest")

// 集約後の実測値（P10b時点: 行95.88% / 分岐84.87%）を下回る「切りの良い」値にして、
// 劣化のみを検知する（他モジュールの下限設定と同じ方針）。
extra["jacocoAggregatedMinLineCoverage"] = 0.90
extra["jacocoAggregatedMinBranchCoverage"] = 0.80
