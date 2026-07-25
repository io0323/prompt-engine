package promptengine.domain.context

/**
 * Context解決の要求仕様（設計書§4.4）。
 */
data class ContextRequirement(
    val scope: String,
    val required: List<String> = emptyList(),
    val optional: List<String> = emptyList(),
) {
    init {
        require(scope.isNotBlank()) { "scope must not be blank" }
        require(required.none { it in optional }) { "required and optional paths must not overlap" }
    }
}
