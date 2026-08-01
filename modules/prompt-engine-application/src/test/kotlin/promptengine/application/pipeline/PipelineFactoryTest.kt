package promptengine.application.pipeline

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.pipeline.PipelineContext
import promptengine.domain.pipeline.PipelineMode
import promptengine.domain.pipeline.PipelineStage

/** [PipelineFactory]の単体テスト（ADR-0015決定6）。 */
class PipelineFactoryTest {
    private fun namedStage(name: String): PipelineStage =
        object : PipelineStage {
            override val name: String = name

            override fun execute(context: PipelineContext): PipelineContext = context
        }

    private val allStageNames =
        listOf(
            "Load", "Merge", "Import", "ResolveVariables", "ResolveContext", "Validation",
            "Optimization", "Rendering", "Execution", "ResponseParsing", "Evaluation", "Audit",
        )
    private val stages = allStageNames.map { namedStage(it) }

    @Test
    fun `12件ちょうどでなければ構築できない`() {
        shouldThrow<IllegalArgumentException> { PipelineFactory(stages.dropLast(1)) }
        shouldThrow<IllegalArgumentException> { PipelineFactory(stages + namedStage("Extra")) }
    }

    @Test
    fun `RENDER_ONLYは1から8ステージのみ`() {
        PipelineFactory(stages).stagesFor(PipelineMode.RENDER_ONLY).map { it.name } shouldBe allStageNames.take(8)
    }

    @Test
    fun `FULL_EXECUTIONは12ステージ全て`() {
        PipelineFactory(stages).stagesFor(PipelineMode.FULL_EXECUTION).map { it.name } shouldBe allStageNames
    }

    @Test
    fun `COMPILE_ONLYは1から3とValidationのみ`() {
        PipelineFactory(stages).stagesFor(PipelineMode.COMPILE_ONLY).map { it.name } shouldBe
            listOf("Load", "Merge", "Import", "Validation")
    }
}
