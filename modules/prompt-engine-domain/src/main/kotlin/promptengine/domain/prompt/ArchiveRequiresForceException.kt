package promptengine.domain.prompt

import promptengine.domain.shared.SemVer

/**
 * `archive`（設計書§2.5、Deprecated→Archived）の呼び出し元が`force=false`だったときに
 * application層のコマンドハンドラが投げる（P9bレビュー指摘、Issue #48）。
 *
 * `execution_logs`（設計書§12）への書き込み経路がM1では未実装で、真の参照クライアント数を
 * 評価する手段が無いため、`Prompt.archive`の`referencingClientCount`ガードに近似値
 * （構造的な依存件数など）を渡すことはせず、`force=true`を明示的に要求する。
 */
class ArchiveRequiresForceException(val promptKey: PromptKey, val semVer: SemVer) :
    IllegalStateException(
        "cannot verify referencing client count for prompt '${promptKey.value}' version '$semVer'; " +
            "archive requires force=true until execution_logs-based verification is implemented (Issue #48)",
    )
