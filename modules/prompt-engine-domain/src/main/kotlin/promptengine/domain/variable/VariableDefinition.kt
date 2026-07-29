package promptengine.domain.variable

/**
 * DSL変数の定義（設計書§4.4）。
 *
 * `sensitive=true`（Secret変数、設計書§2.8「Secret Manager参照名のみDSLに記載」・
 * §15.2）の変数はリテラルの `default` を持てない（ADR-0007）。Secret変数の実値は
 * Render直前にSecret Managerから解決されるものであり、平文の既定値をDSL/DBに
 * 保持すること自体が仕様違反であり、値を秘匿する経路（マスク処理）を後から
 * 個別に用意するより、構築時点で不正な組み合わせを型・不変条件で排除する方が
 * 漏洩経路を構造的に無くせる。
 *
 * [source] はP4のResolver Chainがどのバッキングストアで解決すべきかを示す
 * （設計書§2.8・§15.2、ADR-0011）。`source == SECRET` の変数は必ず `sensitive == true`
 * でなければならない（逆は要求しない）。
 */
data class VariableDefinition(
    val name: String,
    val type: VariableType,
    val source: VariableSource = VariableSource.STATIC,
    val required: Boolean = false,
    val default: Any? = null,
    val constraints: List<String> = emptyList(),
    val sensitive: Boolean = false,
) {
    init {
        require(name.isNotBlank()) { "name must not be blank" }
        require(!(sensitive && default != null)) {
            "sensitive variable must not have a literal default (design doc §2.8/§15.2, ADR-0007): $name"
        }
        require(!(source == VariableSource.SECRET && !sensitive)) {
            "source=SECRET variable must be sensitive (design doc §2.8, ADR-0011): $name"
        }
    }
}
