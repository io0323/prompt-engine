plugins {
    id("promptengine.kotlin-conventions")
}

// prompt-engine-application は prompt-engine-domain のみに依存する（CLAUDE.md）。
dependencies {
    implementation(project(":modules:prompt-engine-domain"))

    // テストのみ: 3モードE2Eテスト（ADR-0015）が具象Engine実装を直接構築するために必要
    // （本番のDI結線はP9でprompt-engine-bootstrapのConfigurationクラスが行う）。
    testImplementation(project(":modules:prompt-engine-core"))
    testImplementation(project(":modules:prompt-engine-infrastructure"))
    testImplementation(project(":plugins:execution-fake"))
    testImplementation(project(":plugins:formatter-json"))
}

// カバレッジ下限（P8実装時点の実測値を元に設定）。
extra["jacocoMinLineCoverage"] = 0.90
extra["jacocoMinBranchCoverage"] = 0.80
