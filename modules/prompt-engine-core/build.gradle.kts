plugins {
    id("promptengine.kotlin-conventions")
}

// prompt-engine-core（Prompt Core）は prompt-engine-domain が定義した Interface を
// 実装する側。逆方向の依存（domain → core）を作らないこと（CLAUDE.md）。
dependencies {
    implementation(project(":modules:prompt-engine-domain"))
    implementation(libs.snakeyaml)
}

// カバレッジ下限（P3aの分岐カバレッジ監査時点の実測: 行97.6% / 分岐88.0%）。
// 実測値を下回る「切りの良い」値にして、劣化のみを検知する。
extra["jacocoMinLineCoverage"] = 0.90
extra["jacocoMinBranchCoverage"] = 0.80
