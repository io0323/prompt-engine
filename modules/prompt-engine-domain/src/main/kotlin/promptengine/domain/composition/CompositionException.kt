package promptengine.domain.composition

import promptengine.domain.fragment.FragmentKey
import promptengine.domain.shared.VersionRange
import promptengine.domain.template.TemplateKey

/**
 * CompositionServiceの解決失敗を表す例外階層（設計書§2.6ステージ2〜3・§13.3、ADR-0009）。
 *
 * 設計書§13.3のエラーコード表には`CIRCULAR_DEPENDENCY`/`TEMPLATE_NOT_FOUND`/
 * `FRAGMENT_NOT_FOUND`のみが定義済みで、それ以外（深さ超過・サイズ超過・Draft参照拒否・
 * マクロ再帰・Nested Prompt未対応）に対応するHTTPコードは存在しない。本フェーズは
 * domain/core層のみが対象であり、HTTPコードへの写像はP9（REST API）実装時に先送りする
 * （GitHub Issue参照、ADR-0009）。
 */
sealed class CompositionException(message: String) : Exception(message)

/** キー参照グラフ上のDFSで、現在の解決チェーン（祖先パス）中のノードへ再訪した（→ 設計書§13.3 `CIRCULAR_DEPENDENCY`）。 */
class CircularDependencyException(val cyclePath: List<String>) :
    CompositionException("circular dependency detected: ${cyclePath.joinToString(" -> ")}")

/**
 * 解決チェーン（extends/import/include/macro展開を通算、ADR-0009決定2）の深さが上限を超過した。
 * P3aのパーサ側ネスト深さ上限（DSL本文の構文木の入れ子数）とは別概念。
 */
class CompositionDepthExceededException(val maxDepth: Int) :
    CompositionException("composition resolution chain exceeds max depth of $maxDepth")

/** 展開後サイズ（設計書§15.5、既定1MB）が上限を超過した。 */
class CompositionSizeExceededException(val maxSizeBytes: Int, val actualSizeBytes: Int) :
    CompositionException("expanded size $actualSizeBytes bytes exceeds maximum of $maxSizeBytes bytes")

/** 範囲[range]にマッチし、かつStatus条件を満たすTemplate Versionが存在しない（→ 設計書§13.3 `TEMPLATE_NOT_FOUND`）。 */
class TemplateReferenceNotFoundException(val key: TemplateKey, val range: VersionRange) :
    CompositionException("no Template version found for ${key.value}@${range.toRangeText() ?: "latest"}")

/** 範囲[range]にマッチし、かつStatus条件を満たすFragment Versionが存在しない（→ 設計書§13.3 `FRAGMENT_NOT_FOUND`）。 */
class FragmentReferenceNotFoundException(val key: FragmentKey, val range: VersionRange) :
    CompositionException("no Fragment version found for ${key.value}@${range.toRangeText() ?: "latest"}")

/**
 * [CompositionMode.COMPILE_ONLY] 以外のモードで、解決候補にDraft版しか存在しなかった
 * （設計書§2.10 DependencyValidationの「Draft相互参照はCompile-onlyで許可」）。
 */
class DraftReferenceNotAllowedException(val referenceDescription: String) :
    CompositionException("Draft reference is not allowed outside COMPILE_ONLY mode: $referenceDescription")

/** macroのローカル展開中に自分自身（直接・間接を問わず）の再呼出を検出した（設計書§15.6「再帰呼出禁止」）。 */
class MacroRecursionException(val macroName: String) :
    CompositionException("macro recursion detected: $macroName")

/**
 * `{{> prompt:key }}`（Nested Prompt、設計書§15.3）は本フェーズのスコープ外（ADR-0009決定3）。
 * GitHub Issue「Nested Promptを実装する」で次フェーズへの回収を追跡する。
 */
class NestedPromptNotSupportedException(val target: String) :
    CompositionException("Nested Prompt is not supported in this phase: $target")
