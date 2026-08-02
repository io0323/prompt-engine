package promptengine.domain.dependency

import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.SemVer

/**
 * `dependencies`テーブル（設計書§12）の1行に対応する依存関係（ADR-0017）。
 *
 * [toKey]/[toVersion]は参照先がTemplate/Fragment/Promptのいずれもあり得るため、
 * それぞれのキー形式・Version範囲表記をそのまま文字列で保持する
 * （`PromptKey`/`SemVer`のバリデーションを参照先の種別ごとに使い分けない）。
 */
data class DependencyEdge(
    val fromKey: PromptKey,
    val fromVersion: SemVer,
    val toKind: DependencyKind,
    val toKey: String,
    val toVersion: String?,
)

/** [DependencyEdge.toKey]が指す参照先の種別。Template/Fragment/Prompt本体のいずれかを表す。 */
enum class DependencyKind { TEMPLATE, FRAGMENT, PROMPT }
