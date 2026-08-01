package promptengine.domain.pipeline

/**
 * [PipelineRequest]が[PipelineMode]の要求を満たさない場合に投げるドメイン例外
 * （設計書§13.3 `INVALID_REQUEST`）。
 *
 * 例: `PipelineMode.FULL_EXECUTION`は`PipelineRequest.executionPolicy`を必須とするが
 * (`PipelineRequest`のKDoc参照)、これは呼出元が修正可能な入力不備であり、
 * サーバ内部の配線不備（`INTERNAL_ERROR`）とは区別する（CodeRabbitレビュー指摘:
 * 従来`ExecutionStage`の`checkNotNull`が`IllegalStateException`を投げ、
 * `StageErrorMapper`のフォールバックで`INTERNAL_ERROR`（500）に写像されていたが、
 * 呼出側の入力不備は本来400番台で伝えるべきである）。`PipelineOrchestrator.run`が
 * ステージ実行前に検証し、この例外を投げる。
 */
class InvalidPipelineRequestException(message: String) : IllegalArgumentException(message)
