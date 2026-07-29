package promptengine.engine.compiler

import promptengine.domain.template.ast.MacroCallNode

/**
 * `{{ super() }}`（引数無しの`super`という名前のmacro呼出）かどうかの判定
 * （[ExtendsMerger]と[MacroExpander]で共有、ADR-0010決定3）。
 */
internal fun isSuperCall(node: MacroCallNode): Boolean = node.name == "super" && node.arguments.isEmpty()
