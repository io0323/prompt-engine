package promptengine.infrastructure.persistence

import promptengine.domain.prompt.Prompt
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
        val contextRequirement: ContextRequirementPayload?,
        val state: String,
    )

    data class ContextRequirementPayload(
        val scope: String,
        val required: List<String>,
        val optional: List<String>,
    )

    /**
     * `sensitive=true` の変数は `default` をマスクする（CLAUDE.md「Secret / sensitive=true の
     * 変数値は絶対に出力しない」）。スナップショットはDBに永続化される監査・復旧用データであり、
     * 平文の秘匿値をJSONBに残さない。
     */
    data class VariablePayload(
        val name: String,
        val type: String,
        val required: Boolean,
        val default: Any?,
        val constraints: List<String>,
        val sensitive: Boolean,
    )

    companion object {
        private const val MASKED_VALUE = "***"

        fun from(prompt: Prompt): PromptSnapshotPayload =
            PromptSnapshotPayload(
                promptKey = prompt.key.value,
                versions =
                    prompt.versions.map { version ->
                        VersionPayload(
                            semVer = version.semVer.toString(),
                            content = version.content.source,
                            variables = version.variables.map { it.toPayload() },
                            contextRequirement =
                                version.contextRequirement?.let {
                                    ContextRequirementPayload(it.scope, it.required, it.optional)
                                },
                            state = version.state.toDbValue(),
                        )
                    },
            )

        private fun VariableDefinition.toPayload() =
            VariablePayload(
                name = name,
                type = type.name,
                required = required,
                default = if (sensitive) MASKED_VALUE else default,
                constraints = constraints,
                sensitive = sensitive,
            )
    }
}
