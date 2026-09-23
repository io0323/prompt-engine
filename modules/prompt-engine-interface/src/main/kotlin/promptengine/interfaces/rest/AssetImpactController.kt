package promptengine.interfaces.rest

import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import promptengine.application.query.GetAssetImpactHandler
import promptengine.application.view.QueryFactory
import promptengine.application.view.handleView

/**
 * Template/Fragmentの変更による影響Prompt一覧エンドポイント（UC-06、設計書§2.5）。
 *
 * - `GET /api/v1/templates/{namespace}/{name}/impact`
 * - `GET /api/v1/fragments/{namespace}/{name}/impact`
 *
 * いずれも「指定したTemplate/Fragmentに（直接・間接を問わず）依存しているPromptのキー一覧」
 * を返す。依存情報はコンパイル時点でPrompt起点に平坦化済み（ADR-0033決定3）のため、
 * DBへの1回のクエリで全依存Promptが求まる（多段グラフ探索は不要）。
 *
 * パスの`{kind}`部分（`templates`/`fragments`）を`GET /api/v1/assets/{kind}/{namespace}/{name}/impact`
 * のような汎用パスにまとめる案も検討したが、REST的な可読性（リソース名の明示性）と
 * OpenAPI定義の見通しを優先して個別パスとした（[PromptController]のKDoc・ADR-0023参照）。
 */
@RestController
@RequestMapping("/api/v1")
class AssetImpactController(private val getAssetImpactHandler: GetAssetImpactHandler) {
    /**
     * [namespace]/[name]で指定したTemplateに依存するPromptのkey一覧を返す。
     * 依存するPromptが0件の場合は空リストを返す。
     */
    @GetMapping("/templates/{namespace}/{name}/impact")
    @PreAuthorize("hasAuthority('prompt:read')")
    fun templateImpact(
        @PathVariable namespace: String,
        @PathVariable name: String,
    ): List<String> = getAssetImpactHandler.handleView(QueryFactory.assetImpactQuery("TEMPLATE", namespace, name))

    /**
     * [namespace]/[name]で指定したFragmentに依存するPromptのkey一覧を返す。
     * 依存するPromptが0件の場合は空リストを返す。
     */
    @GetMapping("/fragments/{namespace}/{name}/impact")
    @PreAuthorize("hasAuthority('prompt:read')")
    fun fragmentImpact(
        @PathVariable namespace: String,
        @PathVariable name: String,
    ): List<String> = getAssetImpactHandler.handleView(QueryFactory.assetImpactQuery("FRAGMENT", namespace, name))
}
