package promptengine.domain.cache

import promptengine.domain.prompt.PromptKey
import java.time.Duration

/**
 * CompiledPromptのキャッシュ（設計書§3.4疑似コード、§16拡張ポイント#9、ADR-0033）。
 * 既定実装はRedis（`prompt-engine-infrastructure`の`RedisPromptCache`）。
 *
 * [get]/[put]は`MergeStage`（Stage 2、STANDARDモードのみ）からのみ呼ばれる
 * （COMPILE_ONLYはキャッシュ対象外、ADR-0033決定a）。
 *
 * [invalidateByPrompt]は`CacheInvalidationSubscriber`が、Prompt/Template/Fragmentの
 * publish系イベントを購読して呼ぶ。Template/Fragment起点の場合は、逆依存
 * （`DependencyRepository`、SemVer範囲一致）で影響を受けるPromptKeyを特定してから呼ぶ
 * （ADR-0033決定3）。`versionRef`を問わず[key]の全エントリを無条件に落とす
 * （[CacheKey]のKDoc参照）。
 */
interface PromptCache {
    /**
     * [key]に対応するキャッシュエントリ。無ければ`null`。
     *
     * NFR-001（Read系はキャッシュで縮退継続）が求める契約: バックエンド（Redis等）への
     * 到達不能・タイムアウト等の実装都合による失敗は、キャッシュミス（`null`）として扱い
     * 呼出元（`MergeStage`）へは伝播させない。「キーが存在しない」場合と「バックエンド障害」
     * 場合を呼出元は区別しない（区別してもMergeStage側の対応＝コンパイルへのフォールバックは
     * 同じであるため、区別する意味がない）。実装は縮退の発生自体を
     * [promptengine.domain.observability.MetricsRecorder.incrementCacheDegradation]で
     * 検知可能にすること（ADR-0033追加決定e）。
     */
    fun get(key: CacheKey): CachedItem?

    /**
     * [key]へ[item]を[ttl]（無効化イベントが届く前に読まれる窓の上限、既定30秒）で保存する。
     *
     * [get]と同じ理由で、書き込み失敗は呼出元に伝播させず、ログ記録の上で処理を継続する
     * （キャッシュへの書き込みが失敗しても、次回以降のリクエストは都度コンパイルされるだけで
     * 正しい結果自体は返せる。書き込み失敗を理由にリクエストを失敗させる必要が無い）。
     */
    fun put(
        key: CacheKey,
        item: CachedItem,
        ttl: Duration,
    )

    /**
     * [key]に紐づくキャッシュエントリを（`versionRef`を問わず）一括で無効化する。
     *
     * [get]/[put]と異なり、この失敗は**古い内容がそのまま配信され続ける**という意味論上の
     * 損害を伴う（呼出元へ伝播させず処理を継続する点は同じだが、影響の質が異なる）。
     * したがって実装は失敗を必ずログとメトリクス
     * （[promptengine.domain.observability.MetricsRecorder.incrementCacheDegradation]）の
     * 両方に残すこと。**[put]の[ttl]が、無効化に失敗した場合の古い内容が生き残れる最終的な
     * 上限として機能する**（無効化が届いても届かなくても、エントリは遅くともTTL経過時点で
     * 消える）。この関係が成立するのは、[put]の[ttl]をいかなる場合も無効化の成否と無関係に
     * 一定値（既定30秒）として設定し続ける実装（`RedisPromptCache`）に限る。
     */
    fun invalidateByPrompt(key: PromptKey)
}
