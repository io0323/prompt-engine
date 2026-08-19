package promptengine.interfaces.rest

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import promptengine.application.command.CancelBenchmarkHandler
import promptengine.application.command.CreateBenchmarkHandler
import promptengine.application.query.GetBenchmarkHandler
import promptengine.application.query.GetBenchmarkResultsHandler
import promptengine.application.view.BenchmarkCommandFactory
import promptengine.application.view.RequestMeta
import promptengine.interfaces.dto.BenchmarkItemResultDto
import promptengine.interfaces.dto.BenchmarkProgressDto
import promptengine.interfaces.dto.BenchmarkResponseDto
import promptengine.interfaces.dto.BenchmarkResultsResponseDto
import promptengine.interfaces.dto.BenchmarkTargetDto
import promptengine.interfaces.dto.CancelBenchmarkResponseDto
import promptengine.interfaces.dto.CreateBenchmarkRequestDto
import promptengine.interfaces.dto.CreateBenchmarkResponseDto
import promptengine.interfaces.support.RequestContext
import promptengine.interfaces.support.TraceIdFilter
import java.util.UUID

/**
 * `/benchmarks`系エンドポイント（設計書§13.1、ADR-0035フェーズ(d)）。
 *
 * スコープは`ExperimentController`と同じ方針で既存の`prompt:*`を再利用する
 * （新規`benchmark:*`は起こさない。ADR-0035はExperiment決定7のような明示的なスコープ決定を
 * 持たないが、Benchmark自体もPromptに従属する評価機能でありExperimentと性質が近いため
 * 同じ判断を踏襲する）: create=`prompt:write`、cancel=`prompt:publish`
 * （Experimentのstart/stopと同じ「運用操作」）、get/results=`prompt:read`。
 */
@RestController
@RequestMapping("/api/v1/benchmarks")
class BenchmarkController(
    private val createBenchmarkHandler: CreateBenchmarkHandler,
    private val getBenchmarkHandler: GetBenchmarkHandler,
    private val cancelBenchmarkHandler: CancelBenchmarkHandler,
    private val getBenchmarkResultsHandler: GetBenchmarkResultsHandler,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('prompt:write')")
    fun create(
        @Valid @RequestBody body: CreateBenchmarkRequestDto,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        authentication: JwtAuthenticationToken,
        request: HttpServletRequest,
    ): CreateBenchmarkResponseDto {
        val meta =
            RequestMeta(RequestContext.actorOf(authentication), TraceIdFilter.traceIdOf(request), idempotencyKey)
        val command =
            BenchmarkCommandFactory.createBenchmarkCommand(
                promptKey = body.promptKey,
                datasetId = body.datasetId,
                targetSemVers = body.targets.map { it.semVer },
                metrics = body.metrics,
                nRepetitions = body.nRepetitions,
                temperature = body.temperature,
                meta = meta,
            )
        val result = createBenchmarkHandler.handle(command)
        return CreateBenchmarkResponseDto(
            benchmarkId = result.benchmarkId.toString(),
            promptKey = result.promptKey,
            status = result.status,
            estimatedExecutionCount = result.estimatedExecutionCount,
        )
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('prompt:read')")
    fun get(
        @PathVariable id: UUID,
    ): BenchmarkResponseDto {
        val view = getBenchmarkHandler.handle(BenchmarkCommandFactory.getBenchmarkQuery(id))
        return BenchmarkResponseDto(
            benchmarkId = view.benchmarkId.toString(),
            promptKey = view.promptKey,
            datasetId = view.datasetId.toString(),
            targets = view.targets.map { BenchmarkTargetDto(it.targetId.toString(), it.semVer) },
            metrics = view.metrics,
            nRepetitions = view.nRepetitions,
            temperature = view.temperature,
            status = view.status,
            estimatedExecutionCount = view.estimatedExecutionCount,
            progress =
                BenchmarkProgressDto(
                    totalItems = view.progress.totalItems,
                    completedItems = view.progress.completedItems,
                    failedItems = view.progress.failedItems,
                    pendingItems = view.progress.pendingItems,
                ),
        )
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('prompt:publish')")
    fun cancel(
        @PathVariable id: UUID,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        authentication: JwtAuthenticationToken,
        request: HttpServletRequest,
    ): CancelBenchmarkResponseDto {
        val meta =
            RequestMeta(RequestContext.actorOf(authentication), TraceIdFilter.traceIdOf(request), idempotencyKey)
        val result = cancelBenchmarkHandler.handle(BenchmarkCommandFactory.cancelBenchmarkCommand(id, meta))
        return CancelBenchmarkResponseDto(result.benchmarkId.toString(), result.status)
    }

    @GetMapping("/{id}/results")
    @PreAuthorize("hasAuthority('prompt:read')")
    fun results(
        @PathVariable id: UUID,
    ): BenchmarkResultsResponseDto {
        val view = getBenchmarkResultsHandler.handle(BenchmarkCommandFactory.getBenchmarkResultsQuery(id))
        return BenchmarkResultsResponseDto(
            benchmarkId = view.benchmarkId.toString(),
            status = view.status,
            items =
                view.items.map {
                    BenchmarkItemResultDto(
                        targetId = it.targetId.toString(),
                        itemId = it.itemId.toString(),
                        status = it.status,
                        accuracyScore = it.accuracyScore?.toString(),
                        consistencyScore = it.consistencyScore?.toString(),
                        determinismScore = it.determinismScore?.toString(),
                        errorMessage = it.errorMessage,
                    )
                },
        )
    }
}
