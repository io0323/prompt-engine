package promptengine.domain.template

/**
 * DSLソース全文から`extends`宣言（設計書§15.1/§15.3）を導出するDomain Service（P9b、Issue #21）。
 *
 * `prompt-engine-application`は`prompt-engine-domain`のみに依存するため、DSL Parser・
 * `ExtendsFieldMapper`（いずれも`prompt-engine-core`）へ直接依存できない。[CompositionService]
 * と同じパターン（domainがInterfaceを持ち、実装は`prompt-engine-core`の
 * `promptengine.engine.compiler`に置き、`prompt-engine-bootstrap`がDI配線する）を踏襲する。
 *
 * Version作成コマンドハンドラ（Prompt/Template共通）は、呼び出し側から`ExtendsRef`を
 * 個別引数として受け取らず、必ずこのポート経由で`content.source`から導出すること
 * （「保存された`ExtendsRef` == `content.source`をパースした結果」という整合性を守るため）。
 */
interface ExtendsFieldResolver {
    /** [source]（DSL全文）のfront matter `extends`宣言を解決する。宣言が無ければ`null`。 */
    fun resolve(source: String): ExtendsRef?
}
