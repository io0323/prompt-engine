plugins {
    id("promptengine.kotlin-conventions")
}

// Plugin実装は prompt-engine-domain と prompt-engine-plugin-api の公開型のみを参照する
// （CLAUDE.md、ADR-0003）。jackson-databindはJSON構文解析専用の実装詳細であり、
// promptengine.plugin.formatter.json の外へは公開しない（ADR-0014）。
dependencies {
    implementation(project(":modules:prompt-engine-domain"))
    implementation(project(":modules:prompt-engine-plugin-api"))
    implementation(libs.jackson.databind)
}

extra["jacocoMinLineCoverage"] = 0.90
extra["jacocoMinBranchCoverage"] = 0.80
