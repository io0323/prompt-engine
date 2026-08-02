package promptengine.domain.audit

import promptengine.domain.pipeline.PipelineMode
import java.time.Instant

/**
 * Pipeline Stage 12（Audit）が追記する記録（設計書§2.6ステージ12、ADR-0015決定7）。
 *
 * 生のprompt/response内容（`RenderedMessage.content`・`RawResponse.content`等）は
 * 一切保持しない。traceId・promptKey・各ステージの所要時間・成否・errorCodeのみを
 * 保持する構造的な最小集合とし（[ExecutionFailedException][promptengine.domain.execution.ExecutionFailedException]・
 * [ParseFailedException][promptengine.domain.parsing.ParseFailedException]が生値を
 * 保持できない構造と同じ設計思想）、ログ・Audit記録経由での秘密情報漏洩を型レベルで防ぐ。
 * §2.6ステージ12「入力: 全ステージ記録」の生データをどこまで拡張して保持するかは
 * [Issue #35](https://github.com/io0323/prompt-engine/issues/35)で検討する。
 */
data class AuditRecord(
    val traceId: String,
    val promptKey: String?,
    val mode: PipelineMode,
    val stageDurationsMs: Map<String, Long>,
    val outcome: AuditOutcome,
    val occurredAt: Instant,
)
