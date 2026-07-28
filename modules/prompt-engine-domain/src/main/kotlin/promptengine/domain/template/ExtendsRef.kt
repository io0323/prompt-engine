package promptengine.domain.template

import promptengine.domain.shared.ExtendsRefApi
import promptengine.domain.shared.VersionRange

/**
 * extends参照（設計書§15.1/§15.3 `extends: <templateKey>[@versionRange]`）。
 * TemplateVersion（Template extends Template）とPromptVersion（Prompt extends Template）の
 * どちらも、参照先は常に [TemplateKey]（ADR-0009）。
 *
 * プライマリコンストラクタは[ExtendsRefApi]でゲートする。DSLソースの`content.source`と
 * 無関係な値を任意に作れてしまうと、「保存された参照 == DSLソースをパースした結果」
 * という整合性が型で保証できなくなるため（ADR-0009）。
 *
 * 既知の限界: コンパイラ自動生成の`copy()`はこのゲートを継承しない（`copy()`に
 * 追随させるにはクラス全体を`@ExtendsRefApi`にする必要があるが、それは`TemplateVersion`/
 * `PromptVersion`等、型として`ExtendsRef`を保持するだけの箇所すべてにOptInの伝播を
 * 要求してしまい、影響範囲がextendsの整合性確保という目的に対して不釣り合いに大きくなる
 * ため見送った）。「既存の正当な`ExtendsRef`を`copy()`で書き換える」バイパス経路が
 * 残ることを許容し、GitHub Issue #21で追跡する。
 */
data class ExtendsRef
    @ExtendsRefApi
    constructor(
        val key: TemplateKey,
        val range: VersionRange = VersionRange.Latest,
    )
