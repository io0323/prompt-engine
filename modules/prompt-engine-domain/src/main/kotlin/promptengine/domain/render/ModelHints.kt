package promptengine.domain.render

/**
 * APAP/Providerの生成パラメータのうち、renderHashに影響しないもの（設計書§2.9、
 * ADR-0013決定6、ADR-0035決定5）。
 *
 * [temperature]はRenderステップの出力バイト（`messages`の内容）に一切影響しない実行時の
 * サンプリングパラメータであるため、[RenderedPrompt.renderHash]の算出には含めない
 * （[promptengine.engine.render.RenderHashCalculator]参照）。将来`top_p`/`seed`等の
 * 生成パラメータを追加できる器として、`ExecutionPolicy`（timeout/retryという実行の
 * 運用方針）とは意図的に型を分離する。
 */
data class ModelHints(val temperature: Double? = null)
