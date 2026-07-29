package promptengine.infrastructure.persistence

import promptengine.domain.prompt.Prompt
import promptengine.domain.validation.ValidationSettings
import promptengine.domain.variable.VariableDefinition

/**
 * `prompt_snapshots.state`（JSONB）に書き込むEvent Storeスナップショットの中身。
 * `Prompt`/`PromptVersion`（`ContextRequirement`等のVOを含む）を直接Jackson直列化せず、
 * infrastructure専用のプレーンなDTOに写す。これにより、domain型のフィールド変更が
 * スナップショットJSONスキーマを直接壊すことを防ぐ。監査・障害復旧用の代替復元経路であり
 * 通常のfindByKeyでは使わない（ADR-0006）ため、逆変換（JSON→domain）は本フェーズでは実装しない。
 */
internal data class PromptSnapshotPayload(
    val promptKey: String,
    val versions: List<VersionPayload>,
) {
    data class VersionPayload(
        val semVer: String,
        val content: String,
        val variables: List<VariablePayload>,
        val contextRequirements: List<ContextRequirementPayload>,
        val validation: ValidationSettings,
        val state: String,
    )

    data class ContextRequirementPayload(
        val scope: String,
        val required: List<String>,
        val optional: List<String>,
    )

    /**
     * `sensitive=true` の変数は `default` を持てない（`VariableDefinition` の不変条件、
     * ADR-0007）ため、ここで平文の秘匿値をマスクする必要はない ── 生成時点でそもそも
     * `default` が `null` であることが保証されている。
     */
    data class VariablePayload(
        val name: String,
        val type: String,
        val source: String,
        val required: Boolean,
        val default: Any?,
        val constraints: List<String>,
        val sensitive: Boolean,
    )

    companion object {
        fun from(prompt: Prompt): PromptSnapshotPayload =
            PromptSnapshotPayload(
                promptKey = prompt.key.value,
                versions =
                    prompt.versions.map { version ->
                        VersionPayload(
                            semVer = version.semVer.toString(),
                            content = version.content.source,
                            variables = version.variables.map { it.toPayload() },
                            contextRequirements =
                                version.contextRequirements.map {
                                    ContextRequirementPayload(it.scope, it.required, it.optional)
                                },
                            validation = version.validation,
                            state = version.state.toDbValue(),
                        )
                    },
            )

        private fun VariableDefinition.toPayload() =
            VariablePayload(
                name = name,
                type = type.name,
                source = source.name,
                required = required,
                default = default,
                constraints = constraints,
                sensitive = sensitive,
            )
    }
}
