plugins {
    id("promptengine.kotlin-conventions")
}

// Golden Prompt回帰テスト（P11）。fixtures/valid配下の.promptサンプルをParse→Compile→Render
// まで通し、renderHashをgolden/配下のファイルへ固定する。DB・Testcontainers不要
// （render pipelineは全てin-memoryで完結する。prompt-engine-core側の既存テストと同じ構成）。
dependencies {
    testImplementation(project(":modules:prompt-engine-domain"))
    testImplementation(project(":modules:prompt-engine-core"))
}
