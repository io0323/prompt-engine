plugins {
    id("promptengine.kotlin-conventions")
}

// prompt-engine-domain は他のいかなるモジュール・フレームワークにも依存しない（CLAUDE.md）。
// 依存を追加する場合は、まずこの制約に反しないか確認すること。

// カバレッジ下限（P3aの分岐カバレッジ監査時点の実測: 行95.1% / 分岐92.4%）。
// 実測値を下回る「切りの良い」値にして、劣化のみを検知する。
extra["jacocoMinLineCoverage"] = 0.90
extra["jacocoMinBranchCoverage"] = 0.85
