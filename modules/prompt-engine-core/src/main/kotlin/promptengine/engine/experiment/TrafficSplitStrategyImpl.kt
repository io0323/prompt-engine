package promptengine.engine.experiment

import promptengine.domain.experiment.Experiment
import promptengine.domain.experiment.TrafficSplitStrategy
import promptengine.domain.experiment.Variant
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * [TrafficSplitStrategy]の既定実装（設計書§16拡張ポイント#12「重み付きランダム+sticky」、
 * ADR-0034決定3）。
 *
 * ## sticky割当のアルゴリズム
 * [stickyKeyValue]が非nullの場合、`SHA-256(experiment.experimentId + ":" + stickyKeyValue)`
 * の先頭8バイトを符号無し64bit整数として解釈し、`% 100`した値を[bucket]（0..99）とする。
 * この[bucket]が各Variantの`weightPct`を[Experiment.variants]の順序（`name`昇順、
 * `TrafficSplitStrategyImpl`の呼出元が[Experiment.variants]をそのまま渡す契約）で
 * 積み上げた累積区間のどこに入るかでVariantを決める。
 *
 * **JDK/Kotlinの実装依存ハッシュ（`Any.hashCode()`等）を使わない。** `hashCode()`の実装は
 * JVMベンダー・バージョン間で仕様上安定性を保証されておらず、「同一キー→同一Variant」が
 * デプロイ・プロセスをまたいで保証されなくなる（ADR-0034決定3）。SHA-256は仕様として
 * 決定的であり、`renderHash`（P6）が採用したのと同じ理由でこの性質を満たす。
 *
 * [stickyKeyValue]が`null`（sticky key未設定、または対応する値が呼出元のリクエストに
 * 無い）の場合は[SecureRandom]による重み付き純粋ランダムにフォールバックする。
 */
class TrafficSplitStrategyImpl(
    private val random: SecureRandom = SecureRandom(),
) : TrafficSplitStrategy {
    override fun select(
        experiment: Experiment,
        stickyKeyValue: String?,
    ): Variant {
        val bucket = if (stickyKeyValue != null) stickyBucket(experiment, stickyKeyValue) else randomBucket()
        return variantForBucket(experiment.variants, bucket)
    }

    private fun stickyBucket(
        experiment: Experiment,
        stickyKeyValue: String,
    ): Int {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest("${experiment.experimentId}:$stickyKeyValue".toByteArray(Charsets.UTF_8))
        val leading = hash.copyOfRange(0, HASH_PREFIX_BYTES)
        val unsigned = BigInteger(1, leading)
        return (unsigned.mod(BigInteger.valueOf(BUCKET_COUNT.toLong()))).toInt()
    }

    private fun randomBucket(): Int = random.nextInt(BUCKET_COUNT)

    /**
     * [buckets]（0..99）を各Variantの`weightPct`累積区間に対応付ける。
     *
     * `bucket`は必ずどこかの区間に入る（`Experiment.create`/`updateTraffic`が
     * 保証する「`weightPct`合計=100」・`bucket`が常に0..99であることから、最後のVariant
     * 処理時点で`cumulative`は必ず100に達し`bucket < cumulative`が成立する）。この保証が
     * 破れる状態はデータ不整合であり、不正な結果を静かに返すより`error`で検知できる方が
     * 安全なため、フォールバックによる`.last()`は置かない（CLAUDE.md「到達不能なコードは
     * 削除する」）。
     */
    private fun variantForBucket(
        variants: List<Variant>,
        bucket: Int,
    ): Variant {
        var cumulative = 0
        for (variant in variants) {
            cumulative += variant.weightPct
            if (bucket < cumulative) return variant
        }
        error("bucket=$bucket did not match any variant interval (weightPct sum invariant violated): $variants")
    }

    private companion object {
        const val BUCKET_COUNT = 100
        const val HASH_PREFIX_BYTES = 8
    }
}
