plugins {
    id("promptengine.kotlin-conventions")
}

// prompt-engine-core（Prompt Core）は prompt-engine-domain が定義した Interface を
// 実装する側。逆方向の依存（domain → core）を作らないこと（CLAUDE.md）。
dependencies {
    implementation(project(":modules:prompt-engine-domain"))
    implementation(libs.snakeyaml)
}
