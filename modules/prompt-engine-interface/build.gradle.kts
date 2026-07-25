plugins {
    id("promptengine.kotlin-conventions")
}

// prompt-engine-interface は prompt-engine-application のみを呼ぶ。
// Repository実装（prompt-engine-infrastructure）に直接触れないこと（CLAUDE.md）。
//
// ルートパッケージ名は promptengine.interfaces（複数形）。
// 設計書§3.1 は promptengine.interface と表記しているが、interface はKotlin/Javaの予約語のため
// 使用できない。詳細は docs/adr/0001-interface-package-naming.md を参照。
dependencies {
    implementation(project(":modules:prompt-engine-application"))
}
