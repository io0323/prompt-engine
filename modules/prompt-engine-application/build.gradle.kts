plugins {
    id("promptengine.kotlin-conventions")
}

// prompt-engine-application は prompt-engine-domain のみに依存する（CLAUDE.md）。
dependencies {
    implementation(project(":modules:prompt-engine-domain"))
}
