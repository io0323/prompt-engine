package promptengine.domain.pipeline

import java.util.UUID

/**
 * Pipeline全体を実行する能力（[PipelineOrchestrator][promptengine.application.pipeline.PipelineOrchestrator]
 * が実装する）を、`prompt-engine-application`に依存できないモジュールから呼べるようにする
 * ためのdomain Interface。
 *
 * `prompt-engine-infrastructure`は`prompt-engine-domain`のみに依存する（CLAUDE.md）ため、
 * Benchmark非同期ワーカー（`prompt-engine-infrastructure`、SLF4Jによるフェンシング喪失ログが
 * 必要でapplication層には置けない、ADR-0035フェーズ(c)）が`PipelineOrchestrator`を直接
 * 呼ぶことはできない。本Interfaceを介して依存を逆転させる。
 */
interface PipelineRunner {
    fun run(
        request: PipelineRequest,
        mode: PipelineMode,
        traceId: String = UUID.randomUUID().toString(),
    ): PipelineContext
}
