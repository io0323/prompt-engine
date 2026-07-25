plugins {
    id("promptengine.kotlin-conventions")
}

// Plugin実装は prompt-engine-plugin-api と prompt-engine-domain の公開型のみを参照する（CLAUDE.md）。
// このモジュール自体も同じ制約に従い、domain 以外のモジュールに依存しない。
dependencies {
    implementation(project(":modules:prompt-engine-domain"))
}
