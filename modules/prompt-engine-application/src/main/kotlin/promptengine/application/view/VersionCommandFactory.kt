package promptengine.application.view

import promptengine.application.command.CreateVersionCommand
import promptengine.application.command.DeprecateCommand
import promptengine.application.command.PublishCommand
import promptengine.application.command.RollbackCommand
import promptengine.application.query.DiffQuery
import promptengine.application.query.GetVersionQuery

/**
 * `VersionController`が使うCommand/Queryを構築する（P9c）。
 *
 * [DomainValueFactory]のKDoc参照（`prompt-engine-interface`がdomain型を直接構築できない理由）。
 */
object VersionCommandFactory {
    fun createVersionCommand(
        core: CreateVersionCoreInput,
        content: PromptVersionContentInput,
        meta: RequestMeta,
    ): CreateVersionCommand =
        CreateVersionCommand(
            key = DomainValueFactory.promptKey(core.key),
            semVer = DomainValueFactory.semVer(core.semVer),
            source = core.source,
            variables = content.variables.map { DomainValueFactory.variableDefinition(it) },
            contextRequirements = content.contextRequirements.map { DomainValueFactory.contextRequirement(it) },
            validation = DomainValueFactory.validationSettings(content.validation),
            output = DomainValueFactory.outputDeclaration(content.output),
            actor = meta.actor,
            traceId = meta.traceId,
            idempotencyKey = meta.idempotencyKey,
        )

    fun publishCommand(
        key: String,
        semVer: String,
        meta: RequestMeta,
    ): PublishCommand =
        PublishCommand(
            DomainValueFactory.promptKey(key),
            DomainValueFactory.semVer(semVer),
            meta.actor,
            meta.traceId,
            meta.idempotencyKey,
        )

    fun rollbackCommand(
        key: String,
        targetSemVer: String,
        meta: RequestMeta,
    ): RollbackCommand =
        RollbackCommand(
            DomainValueFactory.promptKey(key),
            DomainValueFactory.semVer(targetSemVer),
            meta.actor,
            meta.traceId,
            meta.idempotencyKey,
        )

    fun deprecateCommand(
        key: String,
        semVer: String,
        recommendedReplacement: String?,
        meta: RequestMeta,
    ): DeprecateCommand =
        DeprecateCommand(
            key = DomainValueFactory.promptKey(key),
            semVer = DomainValueFactory.semVer(semVer),
            recommendedReplacement = recommendedReplacement?.let { DomainValueFactory.versionRef(it) },
            actor = meta.actor,
            traceId = meta.traceId,
            idempotencyKey = meta.idempotencyKey,
        )

    fun getVersionQuery(
        key: String,
        semVer: String,
    ): GetVersionQuery = GetVersionQuery(DomainValueFactory.promptKey(key), DomainValueFactory.semVer(semVer))

    fun diffQuery(
        key: String,
        from: String,
        to: String,
    ): DiffQuery =
        DiffQuery(DomainValueFactory.promptKey(key), DomainValueFactory.semVer(from), DomainValueFactory.semVer(to))
}
