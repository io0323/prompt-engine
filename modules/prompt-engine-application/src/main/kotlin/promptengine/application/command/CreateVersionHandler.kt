package promptengine.application.command

import promptengine.domain.context.ContextRequirement
import promptengine.domain.dependency.DependencyRepository
import promptengine.domain.event.EventContext
import promptengine.domain.prompt.NewPromptVersion
import promptengine.domain.prompt.PromptContent
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.PromptRepository
import promptengine.domain.prompt.PromptVersionNotFoundException
import promptengine.domain.render.OutputDeclaration
import promptengine.domain.shared.IdempotentCommandExecutor
import promptengine.domain.shared.SemVer
import promptengine.domain.template.ExtendsFieldResolver
import promptengine.domain.validation.ValidationSettings
import promptengine.domain.variable.VariableDefinition
import java.time.Clock
import java.time.Instant

/**
 * `POST /prompts/{key}/versions`（設計書§13.1、新Version作成・Draft）。
 *
 * Issue #21対応: 呼び出し側から`ExtendsRef`を個別引数として受け取らず、必ず
 * [ExtendsFieldResolver]（`content.source`のパース結果）から導出する。
 */
data class CreateVersionCommand(
    val key: PromptKey,
    val semVer: SemVer,
    val source: String,
    val variables: List<VariableDefinition> = emptyList(),
    val contextRequirements: List<ContextRequirement> = emptyList(),
    val validation: ValidationSettings = ValidationSettings(),
    val output: OutputDeclaration? = null,
    val actor: String,
    val traceId: String,
    val idempotencyKey: String? = null,
) {
    internal fun fingerprintPayload(): String =
        listOf(key, semVer, source, variables, contextRequirements, validation, output).toString()
}

data class CreateVersionResult(val key: PromptKey, val semVer: SemVer)

class CreateVersionHandler(
    private val promptRepository: PromptRepository,
    private val dependencyRepository: DependencyRepository,
    private val extendsFieldResolver: ExtendsFieldResolver,
    private val idempotentCommandExecutor: IdempotentCommandExecutor,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun handle(command: CreateVersionCommand): CreateVersionResult =
        idempotentCommandExecutor.executeInTransaction(
            command.idempotencyKey,
            command.fingerprintPayload().sha256(),
            CreateVersionResult::class.java,
        ) {
            val prompt =
                promptRepository.findByKey(command.key)
                    ?: throw PromptVersionNotFoundException.forKey(command.key)
            val content = PromptContent(command.source)
            val extends = extendsFieldResolver.resolve(content.source)
            val newVersion =
                NewPromptVersion(
                    semVer = command.semVer,
                    content = content,
                    variables = command.variables,
                    contextRequirements = command.contextRequirements,
                    extends = extends,
                    validation = command.validation,
                    output = command.output,
                )
            val eventContext =
                EventContext(actor = command.actor, traceId = command.traceId, occurredAt = Instant.now(clock))
            val (updated, event) = prompt.newVersion(newVersion, eventContext)
            val saved = promptRepository.save(updated, listOf(event))
            val edges = dependencyEdgesFrom(command.key, command.semVer, extends)
            dependencyRepository.replaceOutbound(command.key, command.semVer, edges)
            CreateVersionResult(saved.key, command.semVer)
        }
}
