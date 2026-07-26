plugins {
    id("promptengine.kotlin-conventions")
}

// prompt-engine-infrastructure は prompt-engine-domain が定義した Interface を
// 実装する側。逆方向の依存（domain → infrastructure）を作らないこと（CLAUDE.md）。
// Spring Data JDBC / Flyway 等の永続化ライブラリは P2 で追加する。
dependencies {
    implementation(project(":modules:prompt-engine-domain"))
}
