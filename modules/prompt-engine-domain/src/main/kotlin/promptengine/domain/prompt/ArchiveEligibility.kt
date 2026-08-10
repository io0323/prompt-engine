package promptengine.domain.prompt

import promptengine.domain.shared.SemVer
import java.time.Instant

/**
 * `archive`のガード「参照クライアントゼロ確認 or 強制フラグ」（設計書§2.5）を
 * `execution_logs`ベースで判定した結果（Issue #48、ADR-0026決定5）。
 *
 * ## なぜ4値なのか
 * 「`execution_logs`に行が無い」は2つの全く異なる状況を同じ形で表す。
 * 1. 本当に一度も実行されていない（＝参照ゼロ。archiveしてよい）
 * 2. `execution_logs`への書き込み経路がP10bで初めて追加されたため、それ以前に作られた
 *    Versionは実行されていても記録が残っていない（＝判断不能）
 *
 * この2つを区別するため、設定されたカットオーバー時刻（`promptengine.archive.execution-logs-cutover-at`）と
 * `prompt_versions.created_at`を比較する。カットオーバー以前に作られたVersionは
 * [PreCutover]として判断不能扱いにし、従来通り`force=true`を必須にする。
 */
sealed interface ArchiveEligibility {
    /** 対象のVersionが存在しない。 */
    data object VersionNotFound : ArchiveEligibility

    /**
     * Versionの作成時刻がカットオーバー以前。`execution_logs`の不在から参照ゼロを結論できない
     * ため判断不能とし、`force=true`を必須にする（P10b以前のPromptは恒久的にforce専用のまま。
     * ADR-0026決定5で明示的に受け入れた限界）。
     */
    data object PreCutover : ArchiveEligibility

    /** カットオーバー以降のVersionで、判定窓の中に実行記録がある。archiveを拒否する。 */
    data object RecentlyExecuted : ArchiveEligibility

    /** カットオーバー以降のVersionで、判定窓の中に実行記録が無い。`force`無しでのarchiveを許可する。 */
    data object Inactive : ArchiveEligibility
}

/**
 * [ArchiveEligibility]を判定する狭いポート（ADR-0026決定5）。
 *
 * `PromptVersion` Aggregateは`created_at`を公開していない。判定のためだけにAggregateへ
 * 永続化メタデータを持ち込むと、ドメインモデルが永続化の都合で太るため、
 * 「このVersionはガード上archive可能か」という問いだけを答える専用ポートに閉じ込め、
 * `prompt_versions.created_at`と`execution_logs`の突き合わせを
 * `prompt-engine-infrastructure`のSQL側の責務とする。
 */
interface ArchiveEligibilityRepository {
    /**
     * [key] / [semVer]のVersionについて、[cutoverAt]（`execution_logs`が信頼できるようになった
     * 時刻）と[inactiveSince]（判定窓の開始時刻）を基準に[ArchiveEligibility]を返す。
     */
    fun evaluate(
        key: PromptKey,
        semVer: SemVer,
        cutoverAt: Instant,
        inactiveSince: Instant,
    ): ArchiveEligibility
}
