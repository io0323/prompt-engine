package promptengine.domain.prompt

import promptengine.domain.shared.SemVer

/**
 * `archive`（設計書§2.5、Deprecated→Archived）のガードを`force=false`で通過できなかったときに
 * application層のコマンドハンドラが投げる（Issue #48、ADR-0026決定5）。
 *
 * P10bで`execution_logs`への書き込み経路が入り、
 * [ArchiveEligibility.Inactive]（カットオーバー以降に作られ、判定窓の中に実行記録が無い）と
 * 判定できたVersionは`force`無しでarchiveできるようになった。本例外が投げられるのは
 * 残る2ケース:
 * - [ArchiveEligibility.RecentlyExecuted]: 判定窓の中に実行記録がある（＝参照されている）
 * - [ArchiveEligibility.PreCutover]: カットオーバー以前に作られたVersionで、実行記録の不在から
 *   参照ゼロを結論できない（判断不能。恒久的にforce専用のまま。ADR-0026決定5の既知の限界）
 */
class ArchiveRequiresForceException(val promptKey: PromptKey, val semVer: SemVer) :
    IllegalStateException(
        "cannot archive prompt '${promptKey.value}' version '$semVer' without force=true: " +
            "it has a recent execution, or it predates the execution_logs cutover " +
            "and therefore cannot be verified as unreferenced (Issue #48)",
    )
