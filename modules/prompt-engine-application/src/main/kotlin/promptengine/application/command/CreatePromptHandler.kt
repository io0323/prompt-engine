package promptengine.application.command

import promptengine.domain.context.ContextRequirement
import promptengine.domain.dependency.DependencyRepository
import promptengine.domain.event.EventContext
import promptengine.domain.prompt.NewPromptVersion
import promptengine.domain.prompt.Prompt
import promptengine.domain.prompt.PromptContent
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.PromptMetadata
import promptengine.domain.prompt.PromptMetadataRepository
import promptengine.domain.prompt.PromptRepository
import promptengine.domain.render.OutputDeclaration
import promptengine.domain.shared.IdempotentCommandExecutor
import promptengine.domain.shared.SemVer
import promptengine.domain.template.ExtendsFieldResolver
import promptengine.domain.validation.ValidationSettings
import promptengine.domain.variable.VariableDefinition
import java.time.Clock
import java.time.Instant

/** `POST /prompts`（設計書§13.1、Prompt作成・初版Draft）。 */
data class CreatePromptCommand(
    val key: PromptKey,
    val name: String,
    val category: String? = null,
    val description: String? = null,
    val tags: List<String> = emptyList(),
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
    // クラス名を先頭に含める: 他のCommandとfingerprintPayload()の結果が衝突しないため（レビュー指摘）。
    internal fun fingerprintPayload(): String =
        "${this::class.simpleName}:" +
            listOf(
                key, name, category, description, tags, semVer,
                source, variables, contextRequirements, validation, output,
            )
}

data class CreatePromptResult(val key: PromptKey, val semVer: SemVer)

/**
 * ハンドラにビジネスルールを書かない（CLAUDE.md方針）: 状態遷移の妥当性は`Prompt.create`
 * （Aggregate）が判断する。本ハンドラは「データを集めてAggregateに渡し、結果を永続化して
 * イベントを発行する」だけを行う。
 */
class CreatePromptHandler(
    private val promptRepository: PromptRepository,
    private val promptMetadataRepository: PromptMetadataRepository,
    private val dependencyRepository: DependencyRepository,
    private val extendsFieldResolver: ExtendsFieldResolver,
    private val idempotentCommandExecutor: IdempotentCommandExecutor,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun handle(command: CreatePromptCommand): CreatePromptResult =
        idempotentCommandExecutor.executeInTransaction(
            command.idempotencyKey,
            command.fingerprintPayload().sha256(),
            CreatePromptResult::class.java,
        ) {
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
            val (prompt, event) = Prompt.create(command.key, newVersion, eventContext)
            val saved = promptRepository.save(prompt, listOf(event))
            promptMetadataRepository.upsert(
                PromptMetadata(command.key, command.name, command.category, command.description, command.tags),
            )
            val edges = dependencyEdgesFrom(command.key, command.semVer, extends)
            dependencyRepository.replaceOutbound(command.key, command.semVer, edges)
            CreatePromptResult(saved.key, command.semVer)
        }
}
