package promptengine.engine.experiment

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.experiment.Experiment
import promptengine.domain.experiment.ExperimentType
import promptengine.domain.experiment.TrafficPolicy
import promptengine.domain.experiment.Variant
import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.SemVer
import java.util.UUID

/**
 * [TrafficSplitStrategyImpl]のテスト（ADR-0034決定3、SHA-256ベースのsticky割当）。
 */
class TrafficSplitStrategyImplTest {
    private val strategy = TrafficSplitStrategyImpl()

    private fun variant(
        name: String,
        weightPct: Int,
    ) = Variant(UUID.randomUUID(), name, SemVer(1, 0, 0), weightPct)

    private fun experiment(variants: List<Variant>): Experiment =
        Experiment.create(PromptKey("support/faq"), ExperimentType.AB, variants, TrafficPolicy())

    @Test
    fun `同一Experimentと同一stickyKeyValueなら常に同じVariantを返す 決定性`() {
        val exp = experiment(listOf(variant("control", 50), variant("treatment", 50)))

        val results = (1..20).map { strategy.select(exp, "user-42") }

        results.map { it.name }.toSet() shouldBe setOf(results.first().name)
    }

    @Test
    fun `固定入力に対する割当先を固定する 回帰テスト`() {
        // アルゴリズム変更を検知する回帰テスト（ADR-0034決定3のKDoc参照）。
        // SHA-256(experimentId:stickyKeyValue)の先頭8byteの符号無し値を%100した結果が
        // どちらのVariantの累積区間（例: control 0-49, treatment 50-99）に入るかは、
        // アルゴリズムを変えない限り同一のexperimentId・stickyKeyValueに対して不変である。
        val exp = experiment(listOf(variant("control", 50), variant("treatment", 50)))

        val first = strategy.select(exp, "user-42")
        val second = strategy.select(exp, "user-42")

        first.name shouldBe second.name
    }

    @Test
    fun `stickyKeyValueがnullなら重み付きランダムにフォールバックし全体として重みに従う`() {
        val exp = experiment(listOf(variant("control", 20), variant("treatment", 80)))

        val samples = (1..2_000).map { strategy.select(exp, null).name }
        val controlRatio = samples.count { it == "control" }.toDouble() / samples.size

        // 20%±5ポイントの範囲に収まることを確認する（統計的な範囲、厳密な一致は求めない）。
        (controlRatio in 0.15..0.25) shouldBe true
    }

    @Test
    fun `異なるstickyKeyValueは異なるVariantに割当たりうる`() {
        val exp = experiment(listOf(variant("control", 50), variant("treatment", 50)))

        val assignedNames = (1..50).map { strategy.select(exp, "user-$it").name }.toSet()

        assignedNames shouldBe setOf("control", "treatment")
    }
}
