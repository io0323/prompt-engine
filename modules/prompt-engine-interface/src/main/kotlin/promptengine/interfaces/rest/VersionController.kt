package promptengine.interfaces.rest

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import promptengine.application.command.CreateVersionHandler
import promptengine.application.command.DeprecateHandler
import promptengine.application.command.PublishHandler
import promptengine.application.command.RollbackHandler
import promptengine.application.query.DiffHandler
import promptengine.application.query.GetVersionHandler
import promptengine.application.view.CreateVersionCoreInput
import promptengine.application.view.DomainValueFactory
import promptengine.application.view.PromptVersionContentInput
import promptengine.application.view.RequestMeta
import promptengine.application.view.VersionCommandFactory
import promptengine.application.view.handleView
import promptengine.application.view.toView
import promptengine.interfaces.dto.CreateVersionRequestDto
import promptengine.interfaces.dto.DeprecateRequestDto
import promptengine.interfaces.dto.DiffResponseDto
import promptengine.interfaces.dto.KeySemVerResponseDto
import promptengine.interfaces.dto.PromptVersionDto
import promptengine.interfaces.dto.RollbackRequestDto
import promptengine.interfaces.dto.RollbackResponseDto
import promptengine.interfaces.support.RequestContext
import promptengine.interfaces.support.TraceIdFilter

/**
 * `/prompts/{namespace}/{name}/versions`・`diff`・lifecycle遷移エンドポイント（設計書§13.1）。
 *
 * パス変数が`{namespace}`・`{name}`の2つに分かれている理由は[PromptController]のKDoc
 * （ADR-0023参照）。`namespace`/`name`から`PromptKey`文字列表現への復元は各メソッドの先頭で
 * [DomainValueFactory.promptKeyText]を呼ぶことに統一し、他の場所で組み立て直さない。
 *
 * `submit-review`/`approve`/`reject`（Governanceコンテキスト、ADR-0032）は
 * detektの`LongParameterList`閾値対策で[ReviewController]へ分離する（同じ
 * `@RequestMapping`ベースパスを共有する複数Controllerという構成は[PromptController]と
 * 本Controllerの関係と同じ）。
 */
@RestController
@RequestMapping("/api/v1/prompts/{namespace}/{name}")
class VersionController(
    private val createVersionHandler: CreateVersionHandler,
    private val getVersionHandler: GetVersionHandler,
    private val diffHandler: DiffHandler,
    private val publishHandler: PublishHandler,
    private val rollbackHandler: RollbackHandler,
    private val deprecateHandler: DeprecateHandler,
) {
    @Suppress("LongParameterList")
    @PostMapping("/versions")
    @PreAuthorize("hasAuthority('prompt:write')")
    fun createVersion(
        @PathVariable namespace: String,
        @PathVariable name: String,
        @Valid @RequestBody body: CreateVersionRequestDto,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        authentication: JwtAuthenticationToken,
        request: HttpServletRequest,
    ): ResponseEntity<KeySemVerResponseDto> {
        val key = DomainValueFactory.promptKeyText(namespace, name)
        val command =
            VersionCommandFactory.createVersionCommand(
                core = CreateVersionCoreInput(key = key, semVer = body.semVer, source = body.source),
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
        val view = createVersionHandler.handle(command).toView()
        return ResponseEntity.status(HttpStatus.CREATED).body(KeySemVerResponseDto(view.key, view.semVer))
    }

    @GetMapping("/versions/{version}")
    @PreAuthorize("hasAuthority('prompt:read')")
    fun getVersion(
        @PathVariable namespace: String,
        @PathVariable name: String,
        @PathVariable version: String,
    ): PromptVersionDto {
        val key = DomainValueFactory.promptKeyText(namespace, name)
        return getVersionHandler.handleView(VersionCommandFactory.getVersionQuery(key, version)).toDto()
    }

    @GetMapping("/diff")
    @PreAuthorize("hasAuthority('prompt:read')")
    fun diff(
        @PathVariable namespace: String,
        @PathVariable name: String,
        @RequestParam from: String,
        @RequestParam to: String,
    ): DiffResponseDto {
        val key = DomainValueFactory.promptKeyText(namespace, name)
        val view = diffHandler.handleView(VersionCommandFactory.diffQuery(key, from, to))
        return DiffResponseDto(
            view.key,
            view.from,
            view.to,
            view.contentChanged,
            view.fromContentHash,
            view.toContentHash,
            view.variablesChanged,
            view.contextRequirementsChanged,
            view.extendsChanged,
            view.validationChanged,
            view.outputChanged,
        )
    }

    @Suppress("LongParameterList")
    @PostMapping("/versions/{version}/publish")
    @PreAuthorize("hasAuthority('prompt:publish')")
    fun publish(
        @PathVariable namespace: String,
        @PathVariable name: String,
        @PathVariable version: String,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        authentication: JwtAuthenticationToken,
        request: HttpServletRequest,
    ): KeySemVerResponseDto {
        val key = DomainValueFactory.promptKeyText(namespace, name)
        val command =
            VersionCommandFactory.publishCommand(
                key,
                version,
                RequestMeta(RequestContext.actorOf(authentication), TraceIdFilter.traceIdOf(request), idempotencyKey),
            )
        val view = publishHandler.handle(command).toView()
        return KeySemVerResponseDto(view.key, view.semVer)
    }

    @Suppress("LongParameterList")
    @PostMapping("/rollback")
    @PreAuthorize("hasAuthority('prompt:publish')")
    fun rollback(
        @PathVariable namespace: String,
        @PathVariable name: String,
        @Valid @RequestBody body: RollbackRequestDto,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        authentication: JwtAuthenticationToken,
        request: HttpServletRequest,
    ): RollbackResponseDto {
        val key = DomainValueFactory.promptKeyText(namespace, name)
        val command =
            VersionCommandFactory.rollbackCommand(
                key,
                body.targetVersion,
                RequestMeta(RequestContext.actorOf(authentication), TraceIdFilter.traceIdOf(request), idempotencyKey),
            )
        val view = rollbackHandler.handle(command).toView()
        return RollbackResponseDto(view.key, view.targetSemVer)
    }

    /**
     * パラメータ数はHTTPリクエストの構成要素（パス変数3つ・ボディ・ヘッダー・認証情報・
     * リクエスト自体）の直接反映であり、[PromptController.archive]のKDoc参照の理由により
     * `LongParameterList`を許容する。
     */
    @Suppress("LongParameterList")
    @PostMapping("/versions/{version}/deprecate")
    @PreAuthorize("hasAuthority('prompt:publish')")
    fun deprecate(
        @PathVariable namespace: String,
        @PathVariable name: String,
        @PathVariable version: String,
        @RequestBody(required = false) body: DeprecateRequestDto?,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        authentication: JwtAuthenticationToken,
        request: HttpServletRequest,
    ): KeySemVerResponseDto {
        val command =
            VersionCommandFactory.deprecateCommand(
                key = DomainValueFactory.promptKeyText(namespace, name),
                semVer = version,
                recommendedReplacement = body?.recommendedReplacement,
                meta =
                    RequestMeta(
                        RequestContext.actorOf(authentication),
                        TraceIdFilter.traceIdOf(request),
                        idempotencyKey,
                    ),
            )
        val view = deprecateHandler.handle(command).toView()
        return KeySemVerResponseDto(view.key, view.semVer)
    }
}
