package promptengine.application.view

import promptengine.application.command.ArchiveCommand
import promptengine.application.command.CreatePromptCommand
import promptengine.application.command.SetAliasCommand
import promptengine.application.command.UpdatePromptMetadataCommand
import promptengine.application.query.GetPromptQuery
import promptengine.application.query.SearchPromptsQuery
import promptengine.domain.prompt.PromptSearchCriteria

/**
 * `PromptController`・`AliasController`が使うCommand/Queryを構築する（P9c）。
 *
 * [DomainValueFactory]のKDoc参照（`prompt-engine-interface`がdomain型を直接構築できない理由）。
 */
object PromptCommandFactory {
    fun createPromptCommand(
        core: CreatePromptCoreInput,
        content: PromptVersionContentInput,
        meta: RequestMeta,
    ): CreatePromptCommand =
        CreatePromptCommand(
            key = DomainValueFactory.promptKey(core.key),
            name = core.name,
            category = core.category,
            description = core.description,
            tags = core.tags,
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

    fun updatePromptMetadataCommand(
        key: String,
        metadata: UpdatePromptMetadataInput,
        meta: RequestMeta,
    ): UpdatePromptMetadataCommand =
        UpdatePromptMetadataCommand(
            DomainValueFactory.promptKey(key),
            metadata.name,
            metadata.category,
            metadata.description,
            metadata.tags,
            meta.actor,
            meta.traceId,
            meta.idempotencyKey,
        )

    fun archiveCommand(
        key: String,
        semVer: String,
        force: Boolean,
        meta: RequestMeta,
    ): ArchiveCommand =
        ArchiveCommand(
            DomainValueFactory.promptKey(key),
            DomainValueFactory.semVer(semVer),
            force,
            meta.actor,
            meta.traceId,
            meta.idempotencyKey,
        )

    fun setAliasCommand(
        key: String,
        alias: String,
        semVer: String,
        idempotencyKey: String?,
    ): SetAliasCommand =
        SetAliasCommand(DomainValueFactory.promptKey(key), alias, DomainValueFactory.semVer(semVer), idempotencyKey)

    fun getPromptQuery(key: String): GetPromptQuery = GetPromptQuery(DomainValueFactory.promptKey(key))

    fun searchPromptsQuery(
        filters: SearchFiltersInput,
        paging: Paging,
    ): SearchPromptsQuery =
        SearchPromptsQuery(
            PromptSearchCriteria(
                filters.q,
                filters.tag,
                filters.category,
                filters.status?.let { DomainValueFactory.lifecycleState(it) },
                paging.page,
                paging.size,
            ),
        )
}
