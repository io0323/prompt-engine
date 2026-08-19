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
import promptengine.application.command.CreateGoldenDatasetHandler
import promptengine.application.command.GoldenDatasetItemInput
import promptengine.application.query.GetGoldenDatasetHandler
import promptengine.application.view.BenchmarkCommandFactory
import promptengine.application.view.RequestMeta
import promptengine.interfaces.dto.CreateGoldenDatasetRequestDto
import promptengine.interfaces.dto.CreateGoldenDatasetResponseDto
import promptengine.interfaces.dto.GoldenDatasetItemDto
import promptengine.interfaces.dto.GoldenDatasetResponseDto
import promptengine.interfaces.support.RequestContext
import promptengine.interfaces.support.TraceIdFilter
import java.util.UUID

/**
 * `/datasets`系エンドポイント（設計書§13.1、ADR-0035フェーズ(d)）。
 *
 * スコープは[BenchmarkController]と同じ`prompt:*`再利用方針: create=`prompt:write`、
 * get=`prompt:read`。
 */
@RestController
@RequestMapping("/api/v1/datasets")
class GoldenDatasetController(
    private val createGoldenDatasetHandler: CreateGoldenDatasetHandler,
    private val getGoldenDatasetHandler: GetGoldenDatasetHandler,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('prompt:write')")
    fun create(
        @Valid @RequestBody body: CreateGoldenDatasetRequestDto,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        authentication: JwtAuthenticationToken,
        request: HttpServletRequest,
    ): CreateGoldenDatasetResponseDto {
        val meta =
            RequestMeta(RequestContext.actorOf(authentication), TraceIdFilter.traceIdOf(request), idempotencyKey)
        val command =
            BenchmarkCommandFactory.createGoldenDatasetCommand(
                promptKey = body.promptKey,
                name = body.name,
                description = body.description,
                items =
                    body.items.map {
                        GoldenDatasetItemInput(it.parameters, it.context, it.expectedOutput, it.metadata)
                    },
                meta = meta,
            )
        val result = createGoldenDatasetHandler.handle(command)
        return CreateGoldenDatasetResponseDto(result.datasetId.toString(), result.promptKey, result.itemCount)
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('prompt:read')")
    fun get(
        @PathVariable id: UUID,
    ): GoldenDatasetResponseDto {
        val view = getGoldenDatasetHandler.handle(BenchmarkCommandFactory.getGoldenDatasetQuery(id))
        return GoldenDatasetResponseDto(
            datasetId = view.datasetId.toString(),
            promptKey = view.promptKey,
            name = view.name,
            description = view.description,
            items =
                view.items.map {
                    GoldenDatasetItemDto(
                        it.itemId.toString(),
                        it.parameters,
                        it.context,
                        it.expectedOutput,
                        it.metadata,
                    )
                },
        )
    }
}
