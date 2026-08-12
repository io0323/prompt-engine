plugins {
    id("promptengine.kotlin-conventions")
}

// Plugin実装は prompt-engine-domain と prompt-engine-plugin-api の公開型のみを参照する
// （CLAUDE.md、ADR-0003）。OpenAI固有の知識（HTTP形状・認証ヘッダ・JSONスキーマ）は
// このモジュール内に完全に閉じる（M2-1a、ADR-0029）。
//
// M1完了後の暫定実装であり、実APAP接続時にこのモジュール自体が丸ごと不要になる前提
// （ADR-0029決定1）。domain/coreへの依存を最小限（domain実装のみ）に保つのは、
// 削除時の影響範囲をこのディレクトリ内に閉じ込めるため。
dependencies {
    implementation(project(":modules:prompt-engine-domain"))
    implementation(project(":modules:prompt-engine-plugin-api"))
    implementation(libs.jackson.databind)

    testImplementation(libs.wiremock)
}

extra["jacocoMinLineCoverage"] = 0.90
extra["jacocoMinBranchCoverage"] = 0.80
