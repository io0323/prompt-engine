package promptengine.interfaces.rest

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import promptengine.application.command.CreateExperimentHandler
import promptengine.application.command.PromoteExperimentHandler
import promptengine.application.command.StartExperimentHandler
import promptengine.application.command.StopExperimentHandler
import promptengine.application.command.UpdateExperimentTrafficHandler
import promptengine.application.query.GetExperimentResultsHandler
import promptengine.application.view.ExperimentCommandFactory
import promptengine.application.view.RequestMeta
import promptengine.interfaces.dto.CreateExperimentRequestDto
import promptengine.interfaces.dto.ExperimentResponseDto
import promptengine.interfaces.dto.ExperimentResultsResponseDto
import promptengine.interfaces.dto.MetricJudgmentDto
import promptengine.interfaces.dto.PromoteExperimentRequestDto
import promptengine.interfaces.dto.PromoteExperimentResponseDto
import promptengine.interfaces.dto.UpdateExperimentTrafficRequestDto
import promptengine.interfaces.dto.VariantResultDto
import promptengine.interfaces.support.RequestContext
import promptengine.interfaces.support.TraceIdFilter
import java.util.UUID

/**
 * `/experiments`系エンドポイント（設計書§13.1、ADR-0034）。
 *
 * スコープは既存の`prompt:*`を再利用する（ADR-0034決定7、新規`experiment:*`は起こさない。
 * `/prompts/{ns}/{name}/evaluations`が`prompt:read`を再利用するのと同じ扱い）:
 * create=`prompt:write`、start/stop/traffic/promote=`prompt:publish`、results=`prompt:read`。
 */
@RestController
@RequestMapping("/api/v1/experiments")
class ExperimentController(
    private val createExperimentHandler: CreateExperimentHandler,
    private val startExperimentHandler: StartExperimentHandler,
    private val stopExperimentHandler: StopExperimentHandler,
    private val updateExperimentTrafficHandler: UpdateExperimentTrafficHandler,
    private val promoteExperimentHandler: PromoteExperimentHandler,
    private val getExperimentResultsHandler: GetExperimentResultsHandler,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('prompt:write')")
    fun create(
        @Valid @RequestBody body: CreateExperimentRequestDto,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        authentication: JwtAuthenticationToken,
        request: HttpServletRequest,
    ): ExperimentResponseDto {
        val meta =
            RequestMeta(RequestContext.actorOf(authentication), TraceIdFilter.traceIdOf(request), idempotencyKey)
        val command =
            ExperimentCommandFactory.createExperimentCommand(
                promptKey = body.promptKey,
                type = body.type,
                variants = body.variants.map { Triple(it.name, it.semVer, it.weightPct) },
                stickyKeyPath = body.stickyKeyPath,
                meta = meta,
            )
        val result = createExperimentHandler.handle(command)
        return ExperimentResponseDto(result.experimentId.toString(), result.promptKey)
    }

    @PostMapping("/{id}/start")
    @PreAuthorize("hasAuthority('prompt:publish')")
    fun start(
        @PathVariable id: UUID,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        authentication: JwtAuthenticationToken,
        request: HttpServletRequest,
    ): Map<String, String> {
        val meta =
            RequestMeta(RequestContext.actorOf(authentication), TraceIdFilter.traceIdOf(request), idempotencyKey)
        val result = startExperimentHandler.handle(ExperimentCommandFactory.startExperimentCommand(id, meta))
        return mapOf("experimentId" to result.experimentId.toString())
    }

    @PostMapping("/{id}/stop")
    @PreAuthorize("hasAuthority('prompt:publish')")
    fun stop(
        @PathVariable id: UUID,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        authentication: JwtAuthenticationToken,
        request: HttpServletRequest,
    ): Map<String, String> {
        val meta =
            RequestMeta(RequestContext.actorOf(authentication), TraceIdFilter.traceIdOf(request), idempotencyKey)
        val result = stopExperimentHandler.handle(ExperimentCommandFactory.stopExperimentCommand(id, meta))
        return mapOf("experimentId" to result.experimentId.toString())
    }

    @PatchMapping("/{id}/traffic")
    @PreAuthorize("hasAuthority('prompt:publish')")
    fun updateTraffic(
        @PathVariable id: UUID,
        @Valid @RequestBody body: UpdateExperimentTrafficRequestDto,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        authentication: JwtAuthenticationToken,
        request: HttpServletRequest,
    ): Map<String, String> {
        val meta =
            RequestMeta(RequestContext.actorOf(authentication), TraceIdFilter.traceIdOf(request), idempotencyKey)
        val weights = body.weights.map { it.name to it.weightPct }
        val command = ExperimentCommandFactory.updateExperimentTrafficCommand(id, weights, meta)
        val result = updateExperimentTrafficHandler.handle(command)
        return mapOf("experimentId" to result.experimentId.toString())
    }

    @PostMapping("/{id}/promote")
    @PreAuthorize("hasAuthority('prompt:publish')")
    fun promote(
        @PathVariable id: UUID,
        @Valid @RequestBody body: PromoteExperimentRequestDto,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        authentication: JwtAuthenticationToken,
        request: HttpServletRequest,
    ): PromoteExperimentResponseDto {
        val meta =
            RequestMeta(RequestContext.actorOf(authentication), TraceIdFilter.traceIdOf(request), idempotencyKey)
        val command = ExperimentCommandFactory.promoteExperimentCommand(id, body.winnerVariantName, meta)
        val result = promoteExperimentHandler.handle(command)
        return PromoteExperimentResponseDto(result.experimentId.toString(), result.promptKey, result.promotedSemVer)
    }

    @GetMapping("/{id}/results")
    @PreAuthorize("hasAuthority('prompt:read')")
    fun results(
        @PathVariable id: UUID,
    ): ExperimentResultsResponseDto {
        val view = getExperimentResultsHandler.handle(ExperimentCommandFactory.experimentResultsQuery(id))
        return ExperimentResultsResponseDto(
            experimentId = view.experimentId.toString(),
            promptKey = view.promptKey,
            status = view.status,
            controlVariantName = view.controlVariantName,
            variants = view.variants.map { VariantResultDto(it.variantName, it.weightPct, it.isControl) },
            judgments =
                view.judgments.map {
                    MetricJudgmentDto(
                        metricType = it.metricType,
                        challengerVariantName = it.challengerVariantName,
                        controlSampleSize = it.controlSampleSize,
                        challengerSampleSize = it.challengerSampleSize,
                        controlMean = it.controlMean?.toString(),
                        challengerMean = it.challengerMean?.toString(),
                        pValue = it.pValue,
                        sufficientSample = it.sufficientSample,
                        significant = it.significant,
                    )
                },
        )
    }
}
