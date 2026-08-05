package promptengine.interfaces.rest

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import promptengine.application.command.ArchiveHandler
import promptengine.application.command.CreatePromptHandler
import promptengine.application.command.UpdatePromptMetadataHandler
import promptengine.application.query.GetPromptHandler
import promptengine.application.query.SearchPromptsHandler
import promptengine.application.view.CreatePromptCoreInput
import promptengine.application.view.DomainValueFactory
import promptengine.application.view.Paging
import promptengine.application.view.PromptCommandFactory
import promptengine.application.view.PromptVersionContentInput
import promptengine.application.view.RequestMeta
import promptengine.application.view.SearchFiltersInput
import promptengine.application.view.UpdatePromptMetadataInput
import promptengine.application.view.handleView
import promptengine.application.view.toView
import promptengine.interfaces.dto.CreatePromptRequestDto
import promptengine.interfaces.dto.KeyOnlyResponseDto
import promptengine.interfaces.dto.KeySemVerResponseDto
import promptengine.interfaces.dto.PageDto
import promptengine.interfaces.dto.PromptDetailDto
import promptengine.interfaces.dto.PromptSearchQueryParams
import promptengine.interfaces.dto.PromptSummaryDto
import promptengine.interfaces.dto.UpdatePromptMetadataRequestDto
import promptengine.interfaces.support.RequestContext
import promptengine.interfaces.support.TraceIdFilter

/**
 * `/prompts`のCRUD/検索エンドポイント（設計書§13.1）。
 *
 * Version配下・エイリアス・依存関係・メトリクス・Pipeline系エンドポイント（本ファイル以外の
 * `*Controller`）を含め、`PromptKey`を含む全パスは`/{namespace}/{name}`という2つの独立した
 * パス変数で受け取る（単一の`{key}`ではない）。`PromptKey`は設計上`namespace/name`
 * （スラッシュ区切り）が必須（[promptengine.domain.prompt.PromptKey]・設計書§4.4）だが、
 * Spring MVCの`PathPatternParser`は1つのパス変数がセグメント境界（`/`）をまたぐマッチングを
 * サポートしないため、単一の`{key}`ではURLエンコード（`%2F`）を要求する以外に表現できない
 * （9c初回のE2E確認で発覚、ADR-0023で案A「`{key:.+}`可変長パターン」・案B「`%2F`必須+
 * デコード許可」を検討し、いずれも退けて本形式を採用した理由を記録）。
 *
 * `namespace`・`name`から`PromptKey`文字列表現への復元は
 * [promptengine.application.view.DomainValueFactory.promptKeyText]に一元化し、各Controllerで
 * `"$namespace/$name"`を組み立て直さない。
 */
@RestController
@RequestMapping("/api/v1/prompts")
class PromptController(
    private val createPromptHandler: CreatePromptHandler,
    private val updatePromptMetadataHandler: UpdatePromptMetadataHandler,
    private val archiveHandler: ArchiveHandler,
    private val getPromptHandler: GetPromptHandler,
    private val searchPromptsHandler: SearchPromptsHandler,
) {
    @PostMapping
    @PreAuthorize("hasAuthority('prompt:write')")
    fun create(
        @Valid @RequestBody body: CreatePromptRequestDto,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        authentication: JwtAuthenticationToken,
        request: HttpServletRequest,
    ): ResponseEntity<KeySemVerResponseDto> {
        val command =
            PromptCommandFactory.createPromptCommand(
                core =
                    CreatePromptCoreInput(
                        key = body.key,
                        name = body.name,
                        category = body.category,
                        description = body.description,
                        tags = body.tags,
                        semVer = body.semVer,
                        source = body.source,
                    ),
                content =
                    PromptVersionContentInput(
                        variables = body.variables.map { it.toInput() },
                        contextRequirements = body.contextRequirements.map { it.toInput() },
                        validation = body.validation?.toInput(),
                        output = body.output?.toInput(),
                    ),
                meta =
                    RequestMeta(
                        RequestContext.actorOf(authentication),
                        TraceIdFilter.traceIdOf(request),
                        idempotencyKey,
                    ),
            )
        val view = createPromptHandler.handle(command).toView()
        return ResponseEntity.status(HttpStatus.CREATED).body(KeySemVerResponseDto(view.key, view.semVer))
    }

    @GetMapping
    @PreAuthorize("hasAuthority('prompt:read')")
    fun search(
        @ModelAttribute params: PromptSearchQueryParams,
    ): PageDto<PromptSummaryDto> {
        val query =
            PromptCommandFactory.searchPromptsQuery(
                SearchFiltersInput(params.q, params.tag, params.category, params.status),
                Paging(params.page, params.size),
            )
        val view = searchPromptsHandler.handleView(query)
        return PageDto(view.items.map { it.toDto() }, view.page, view.size, view.totalElements)
    }

    @GetMapping("/{namespace}/{name}")
    @PreAuthorize("hasAuthority('prompt:read')")
    fun get(
        @PathVariable namespace: String,
        @PathVariable name: String,
    ): PromptDetailDto {
        val key = DomainValueFactory.promptKeyText(namespace, name)
        val view = getPromptHandler.handle(PromptCommandFactory.getPromptQuery(key)).toView()
        return PromptDetailDto(view.metadata?.toDto(), view.versions.map { it.toDto() })
    }

    /**
     * パラメータ数は[archive]のKDoc参照の理由により`LongParameterList`を許容する
     * （`namespace`/`name`への分割、ADR-0023参照、で1個増えた）。
     */
    @Suppress("LongParameterList")
    @PatchMapping("/{namespace}/{name}")
    @PreAuthorize("hasAuthority('prompt:write')")
    fun updateMetadata(
        @PathVariable namespace: String,
        @PathVariable name: String,
        @Valid @RequestBody body: UpdatePromptMetadataRequestDto,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        authentication: JwtAuthenticationToken,
        request: HttpServletRequest,
    ): KeyOnlyResponseDto {
        val command =
            PromptCommandFactory.updatePromptMetadataCommand(
                key = DomainValueFactory.promptKeyText(namespace, name),
                metadata = UpdatePromptMetadataInput(body.name, body.category, body.description, body.tags),
                meta =
                    RequestMeta(
                        RequestContext.actorOf(authentication),
                        TraceIdFilter.traceIdOf(request),
                        idempotencyKey,
                    ),
            )
        val view = updatePromptMetadataHandler.handle(command).toView()
        return KeyOnlyResponseDto(view.key)
    }

    /**
     * `DELETE /prompts/{namespace}/{name}`（設計書§13.1、Archive）。設計書の表はVersionを指定する
     * パスパラメータを持たない一方、`Prompt.archive`（Aggregate）はVersion単位の操作である
     * （設計書§2.5）。この差を埋めるため、`version`クエリパラメータを必須とする実装判断を採る
     * （P9c、コミットメッセージ記載事項）。`force`はデフォルト`false`とし、参照クライアント数を
     * 検証できないM1の制約（Issue #48）をクライアントに明示的に受諾させる。
     *
     * パラメータ数はHTTPリクエストの構成要素（パス変数2つ・クエリパラメータ・ヘッダー・
     * 認証情報・リクエスト自体）の直接反映であり、`@RequestHeader`/`JwtAuthenticationToken`/
     * `HttpServletRequest`はSpring MVCの引数解決の都合上、任意のPOJOへ束ねられない
     * （`@ModelAttribute`が対応するのはクエリ/パス由来の値のみ）ため`LongParameterList`を許容する。
     */
    @Suppress("LongParameterList")
    @DeleteMapping("/{namespace}/{name}")
    @PreAuthorize("hasAuthority('prompt:admin')")
    fun archive(
        @PathVariable namespace: String,
        @PathVariable name: String,
        @RequestParam version: String,
        @RequestParam(defaultValue = "false") force: Boolean,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        authentication: JwtAuthenticationToken,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        val command =
            PromptCommandFactory.archiveCommand(
                key = DomainValueFactory.promptKeyText(namespace, name),
                semVer = version,
                force = force,
                meta =
                    RequestMeta(
                        RequestContext.actorOf(authentication),
                        TraceIdFilter.traceIdOf(request),
                        idempotencyKey,
                    ),
            )
        archiveHandler.handle(command)
        return ResponseEntity.noContent().build()
    }
}
