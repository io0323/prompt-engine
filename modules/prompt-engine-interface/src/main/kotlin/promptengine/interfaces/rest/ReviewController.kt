package promptengine.interfaces.rest

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import promptengine.application.command.ApproveHandler
import promptengine.application.command.RejectHandler
import promptengine.application.command.SubmitReviewHandler
import promptengine.application.view.DomainValueFactory
import promptengine.application.view.RequestMeta
import promptengine.application.view.VersionCommandFactory
import promptengine.application.view.toView
import promptengine.interfaces.dto.ApproveRequestDto
import promptengine.interfaces.dto.KeySemVerResponseDto
import promptengine.interfaces.dto.RejectRequestDto
import promptengine.interfaces.support.RequestContext
import promptengine.interfaces.support.TraceIdFilter

/**
 * `submit-review`/`approve`/`reject`エンドポイント（設計書§13.1、Governanceコンテキスト、
 * ADR-0004・ADR-0032）。[VersionController]と同じベースパスを共有する別Controller
 * （detektの`LongParameterList`閾値対策、[VersionController]のKDoc参照）。
 *
 * スコープは設計書§13.1のとおり: submit-review=`prompt:write`（著者自身が行う提出操作）、
 * approve=`prompt:approve`、reject=`prompt:review`。
 */
@RestController
@RequestMapping("/api/v1/prompts/{namespace}/{name}")
class ReviewController(
    private val submitReviewHandler: SubmitReviewHandler,
    private val approveHandler: ApproveHandler,
    private val rejectHandler: RejectHandler,
) {
    @Suppress("LongParameterList")
    @PostMapping("/versions/{version}/submit-review")
    @PreAuthorize("hasAuthority('prompt:write')")
    fun submitReview(
        @PathVariable namespace: String,
        @PathVariable name: String,
        @PathVariable version: String,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        authentication: JwtAuthenticationToken,
        request: HttpServletRequest,
    ): KeySemVerResponseDto {
        val key = DomainValueFactory.promptKeyText(namespace, name)
        val command =
            VersionCommandFactory.submitReviewCommand(
                key,
                version,
                RequestMeta(RequestContext.actorOf(authentication), TraceIdFilter.traceIdOf(request), idempotencyKey),
            )
        val view = submitReviewHandler.handle(command).toView()
        return KeySemVerResponseDto(view.key, view.semVer)
    }

    @Suppress("LongParameterList")
    @PostMapping("/versions/{version}/approve")
    @PreAuthorize("hasAuthority('prompt:approve')")
    fun approve(
        @PathVariable namespace: String,
        @PathVariable name: String,
        @PathVariable version: String,
        @RequestBody(required = false) body: ApproveRequestDto?,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        authentication: JwtAuthenticationToken,
        request: HttpServletRequest,
    ): KeySemVerResponseDto {
        val key = DomainValueFactory.promptKeyText(namespace, name)
        val command =
            VersionCommandFactory.approveCommand(
                key,
                version,
                body?.comment,
                RequestMeta(RequestContext.actorOf(authentication), TraceIdFilter.traceIdOf(request), idempotencyKey),
            )
        val view = approveHandler.handle(command).toView()
        return KeySemVerResponseDto(view.key, view.semVer)
    }

    @Suppress("LongParameterList")
    @PostMapping("/versions/{version}/reject")
    @PreAuthorize("hasAuthority('prompt:review')")
    fun reject(
        @PathVariable namespace: String,
        @PathVariable name: String,
        @PathVariable version: String,
        @Valid @RequestBody body: RejectRequestDto,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        authentication: JwtAuthenticationToken,
        request: HttpServletRequest,
    ): KeySemVerResponseDto {
        val key = DomainValueFactory.promptKeyText(namespace, name)
        val command =
            VersionCommandFactory.rejectCommand(
                key,
                version,
                body.comment,
                RequestMeta(RequestContext.actorOf(authentication), TraceIdFilter.traceIdOf(request), idempotencyKey),
            )
        val view = rejectHandler.handle(command).toView()
        return KeySemVerResponseDto(view.key, view.semVer)
    }
}
