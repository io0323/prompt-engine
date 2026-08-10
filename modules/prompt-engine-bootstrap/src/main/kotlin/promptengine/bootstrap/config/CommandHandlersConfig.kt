package promptengine.bootstrap.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import promptengine.application.command.ArchiveHandler
import promptengine.application.command.CreatePromptHandler
import promptengine.application.command.CreateVersionHandler
import promptengine.application.command.DeprecateHandler
import promptengine.application.command.DiscardHandler
import promptengine.application.command.PublishHandler
import promptengine.application.command.RollbackHandler
import promptengine.application.command.SetAliasHandler
import promptengine.application.command.UpdatePromptMetadataHandler
import promptengine.domain.audit.AuditRepository
import promptengine.domain.dependency.DependencyRepository
import promptengine.domain.fragment.FragmentRepository
import promptengine.domain.prompt.ArchiveEligibilityRepository
import promptengine.domain.prompt.PromptAliasRepository
import promptengine.domain.prompt.PromptMetadataRepository
import promptengine.domain.prompt.PromptRepository
import promptengine.domain.shared.IdempotentCommandExecutor
import promptengine.domain.template.ExtendsFieldResolver
import promptengine.domain.template.TemplateRepository

/**
 * P9bのCommandハンドラのDI配線（P9c）。設計書§13.1のエンドポイントと1:1対応する
 * （[QueryHandlersConfig]・[ApplicationHandlersConfig.kt][promptengine.bootstrap.config]の
 * KDoc参照。detekt TooManyFunctions閾値対策でCommand/Queryに分割）。
 */
@Configuration
@EnableConfigurationProperties(ArchiveGuardProperties::class)
class CommandHandlersConfig {
    @Bean
    fun createPromptHandler(
        promptRepository: PromptRepository,
        promptMetadataRepository: PromptMetadataRepository,
        dependencyRepository: DependencyRepository,
        extendsFieldResolver: ExtendsFieldResolver,
        idempotentCommandExecutor: IdempotentCommandExecutor,
    ): CreatePromptHandler =
        CreatePromptHandler(
            promptRepository,
            promptMetadataRepository,
            dependencyRepository,
            extendsFieldResolver,
            idempotentCommandExecutor,
        )

    @Bean
    fun updatePromptMetadataHandler(
        promptMetadataRepository: PromptMetadataRepository,
        idempotentCommandExecutor: IdempotentCommandExecutor,
    ): UpdatePromptMetadataHandler = UpdatePromptMetadataHandler(promptMetadataRepository, idempotentCommandExecutor)

    @Bean
    fun createVersionHandler(
        promptRepository: PromptRepository,
        dependencyRepository: DependencyRepository,
        extendsFieldResolver: ExtendsFieldResolver,
        idempotentCommandExecutor: IdempotentCommandExecutor,
    ): CreateVersionHandler =
        CreateVersionHandler(promptRepository, dependencyRepository, extendsFieldResolver, idempotentCommandExecutor)

    @Bean
    fun publishHandler(
        promptRepository: PromptRepository,
        templateRepository: TemplateRepository,
        fragmentRepository: FragmentRepository,
        dependencyRepository: DependencyRepository,
        idempotentCommandExecutor: IdempotentCommandExecutor,
    ): PublishHandler =
        PublishHandler(
            promptRepository,
            templateRepository,
            fragmentRepository,
            dependencyRepository,
            idempotentCommandExecutor,
        )

    @Bean
    fun rollbackHandler(
        promptRepository: PromptRepository,
        idempotentCommandExecutor: IdempotentCommandExecutor,
    ): RollbackHandler = RollbackHandler(promptRepository, idempotentCommandExecutor)

    @Bean
    fun deprecateHandler(
        promptRepository: PromptRepository,
        idempotentCommandExecutor: IdempotentCommandExecutor,
    ): DeprecateHandler = DeprecateHandler(promptRepository, idempotentCommandExecutor)

    @Bean
    fun discardHandler(
        promptRepository: PromptRepository,
        idempotentCommandExecutor: IdempotentCommandExecutor,
    ): DiscardHandler = DiscardHandler(promptRepository, idempotentCommandExecutor)

    /**
     * `execution_logs`ベースのarchiveガード（Issue #48、ADR-0026決定5）に必要な
     * [ArchiveEligibilityRepository]と設定を注入する。設定のバインディング
     * （[ArchiveGuardProperties]）はbootstrapに閉じ、ハンドラ自身は素の
     * [promptengine.domain.prompt.ArchiveGuardSettings]だけを受け取る。
     */
    @Bean
    fun archiveHandler(
        promptRepository: PromptRepository,
        dependencyRepository: DependencyRepository,
        idempotentCommandExecutor: IdempotentCommandExecutor,
        archiveEligibilityRepository: ArchiveEligibilityRepository,
        archiveGuardProperties: ArchiveGuardProperties,
    ): ArchiveHandler =
        ArchiveHandler(
            promptRepository,
            dependencyRepository,
            idempotentCommandExecutor,
            archiveEligibilityRepository,
            archiveGuardProperties.toSettings(),
        )

    @Bean
    fun setAliasHandler(
        promptRepository: PromptRepository,
        promptAliasRepository: PromptAliasRepository,
        auditRepository: AuditRepository,
        idempotentCommandExecutor: IdempotentCommandExecutor,
    ): SetAliasHandler =
        SetAliasHandler(promptRepository, promptAliasRepository, auditRepository, idempotentCommandExecutor)
}
