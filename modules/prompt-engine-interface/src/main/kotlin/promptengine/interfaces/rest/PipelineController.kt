package promptengine.interfaces.rest

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import promptengine.application.pipeline.CompileUseCase
import promptengine.application.pipeline.ExecuteUseCase
import promptengine.application.pipeline.PipelineRequestFactory
import promptengine.application.pipeline.PipelineRequestInput
import promptengine.application.pipeline.RenderUseCase
import promptengine.application.view.DomainValueFactory
import promptengine.application.view.ExecutionPolicyInput
import promptengine.application.view.OutputSchemaFieldInput
import promptengine.application.view.OutputSchemaInput
import promptengine.interfaces.dto.CompileResponseDto
import promptengine.interfaces.dto.ExecuteRequestDto
import promptengine.interfaces.dto.ExecuteResponseDto
import promptengine.interfaces.dto.FindingDto
import promptengine.interfaces.dto.OutputSchemaDto
import promptengine.interfaces.dto.RenderRequestDto
import promptengine.interfaces.dto.RenderResponseDto
import promptengine.interfaces.dto.RenderedMessageDto
import promptengine.interfaces.dto.UsageDto
import promptengine.interfaces.support.TraceIdFilter

/**
 * `POST /prompts/{namespace}/{name}/compile|render|execute`（設計書§13.1・§13.2、
 * [PromptController]のKDoc参照）。
 */
@RestController
@RequestMapping("/api/v1/prompts/{namespace}/{name}")
class PipelineController(
    private val pipelineRequestFactory: PipelineRequestFactory,
    private val compileUseCase: CompileUseCase,
    private val renderUseCase: RenderUseCase,
    private val executeUseCase: ExecuteUseCase,
) {
    @PostMapping("/compile")
    @PreAuthorize("hasAuthority('prompt:read')")
    fun compile(
        @PathVariable namespace: String,
        @PathVariable name: String,
        @Valid @RequestBody body: RenderRequestDto,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        request: HttpServletRequest,
    ): CompileResponseDto {
        val key = DomainValueFactory.promptKeyText(namespace, name)
        val traceId = TraceIdFilter.traceIdOf(request)
        val command = pipelineRequestFactory.createCompileCommand(body.toInput(key), traceId, idempotencyKey)
        val result = compileUseCase.handle(command)
        return CompileResponseDto(result.promptKey, result.traceId, result.validationPassed, result.warningCount)
    }

    @PostMapping("/render")
    @PreAuthorize("hasAuthority('prompt:read')")
    fun render(
        @PathVariable namespace: String,
        @PathVariable name: String,
        @Valid @RequestBody body: RenderRequestDto,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        request: HttpServletRequest,
    ): RenderResponseDto {
        val key = DomainValueFactory.promptKeyText(namespace, name)
        val traceId = TraceIdFilter.traceIdOf(request)
        val command = pipelineRequestFactory.createRenderCommand(body.toInput(key), traceId, idempotencyKey)
        val result = renderUseCase.handle(command)
        return RenderResponseDto(
            promptKey = result.promptKey,
            version = result.version,
            messages = result.messages.map { RenderedMessageDto(it.role, it.content) },
            outputFormat = result.outputFormat,
            outputSchemaRef = result.outputSchemaRef,
            tokenEstimate = result.tokenEstimate,
            renderHash = result.renderHash,
            warnings = result.warnings.map { FindingDto(it.ruleId, it.path, it.severity, it.message) },
            traceId = result.traceId,
        )
    }

    @PostMapping("/execute")
    @PreAuthorize("hasAuthority('prompt:execute')")
    fun execute(
        @PathVariable namespace: String,
        @PathVariable name: String,
        @Valid @RequestBody body: ExecuteRequestDto,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        request: HttpServletRequest,
    ): ExecuteResponseDto {
        val key = DomainValueFactory.promptKeyText(namespace, name)
        val traceId = TraceIdFilter.traceIdOf(request)
        val input =
            PipelineRequestInput(
                promptKey = key,
                versionRef = body.versionRef,
                explicitParameters = body.parameters,
                contextData = body.context,
                budget = body.options?.tokenBudget ?: DEFAULT_TOKEN_BUDGET,
                outputFormat = null,
                outputSchema = body.outputSchema?.toInput(),
                executionPolicy =
                    ExecutionPolicyInput(
                        body.executionPolicy.timeoutMs,
                        body.executionPolicy.maxRetries,
                        body.executionPolicy.parseRepair,
                    ),
            )
        val command = pipelineRequestFactory.createExecuteCommand(input, traceId, idempotencyKey)
        val result = executeUseCase.handle(command)
        return ExecuteResponseDto(
            promptKey = result.promptKey,
            version = result.version,
            outputFormat = result.outputFormat,
            parsedOutput = result.parsedOutput,
            rawContent = result.rawContent,
            usage = result.usage?.let { UsageDto(it.inputTokens, it.outputTokens, it.cost) },
            latencyMs = result.latencyMs,
            evaluationId = result.evaluationId,
            traceId = result.traceId,
        )
    }

    private fun RenderRequestDto.toInput(key: String): PipelineRequestInput =
        PipelineRequestInput(
            promptKey = key,
            versionRef = versionRef,
            explicitParameters = parameters,
            contextData = context,
            budget = options?.tokenBudget ?: DEFAULT_TOKEN_BUDGET,
        )

    private fun OutputSchemaDto.toInput(): OutputSchemaInput =
        OutputSchemaInput(id, fields.map { OutputSchemaFieldInput(it.name, it.type, it.required) })

    private companion object {
        const val DEFAULT_TOKEN_BUDGET = 8_000
    }
}
