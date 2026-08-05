package promptengine.domain.prompt

import promptengine.domain.shared.SemVer

/**
 * `VersionRef.Fixed`/`VersionRef.Alias`で解決した[PromptVersion]の状態が、要求元の
 * `PipelineMode`では参照を許可されない場合に投げるドメイン例外（設計書§2.13、ADR-0024）。
 *
 * `COMPILE_ONLY`以外（`RENDER_ONLY`/`FULL_EXECUTION`）では`Published`/`Deprecated`の
 * Versionのみ参照可能とする。P3c `CompositionService`が確立した「Draft相互参照は
 * COMPILE_ONLYでのみ許可」というルール（[promptengine.domain.composition.DraftReferenceNotAllowedException]、
 * ADR-0009/0012）を、Compositionが解決する依存（Template/Fragment参照）だけでなく、
 * クライアントが直接指定する主`PromptVersion`自体の解決（Stage 1 Load）にも適用する。
 *
 * `IllegalArgumentException`ではなく`RuntimeException`を継承する: `GlobalExceptionHandler`
 * （`prompt-engine-interface`）は`IllegalArgumentException`を「フレームワークレベルの
 * 不正リクエスト」として直接`INVALID_REQUEST`(400)へ写像するハンドラを持つため、
 * これを継承すると[promptengine.application.pipeline.StageErrorMapper]
 * （`VALIDATION_FAILED`への写像、ADR-0024）を経由せず意図しないコードで応答してしまう。
 */
class PromptVersionStateNotAllowedException(val semVer: SemVer, val state: LifecycleState) :
    RuntimeException(
        "PromptVersion $semVer is in state ${state::class.simpleName}; only Published/Deprecated " +
            "versions can be referenced outside COMPILE_ONLY mode",
    )
