plugins {
    id("promptengine.kotlin-conventions")
}

// 他モジュールのテストから利用する共通フィクスチャ・ビルダーを提供する。
// 利用側の src/test で使えるよう、domain / kotest / mockk は api として公開する。
dependencies {
    api(project(":modules:prompt-engine-domain"))
    api(libs.kotest.assertions.core)
    api(libs.mockk)
}
