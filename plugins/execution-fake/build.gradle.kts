plugins {
    id("promptengine.kotlin-conventions")
}

// Plugin実装は prompt-engine-domain と prompt-engine-plugin-api の公開型のみを参照する
// （CLAUDE.md、ADR-0003）。
dependencies {
    implementation(project(":modules:prompt-engine-domain"))
    implementation(project(":modules:prompt-engine-plugin-api"))
}

extra["jacocoMinLineCoverage"] = 0.90
extra["jacocoMinBranchCoverage"] = 0.80
