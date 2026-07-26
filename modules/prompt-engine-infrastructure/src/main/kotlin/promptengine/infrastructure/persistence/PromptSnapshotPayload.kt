package promptengine.infrastructure.persistence

import promptengine.domain.context.ContextRequirement
import promptengine.domain.prompt.Prompt

/**
 * `prompt_snapshots.state`（JSONB）に書き込むEvent Storeスナップショットの中身。
 * `Prompt`/`PromptVersion` を直接Jackson直列化せず、infrastructure専用のプレーンな
 * DTOに写す。監査・障害復旧用の代替復元経路であり通常のfindByKeyでは使わない
 * （ADR-0006）ため、逆変換（JSON→domain）は本フェーズでは実装しない。
 */
internal data class PromptSnapshotPayload(
    val promptKey: String,
    val versions: List<VersionPayload>,
) {
    data class VersionPayload(
        val semVer: String,
        val content: String,
        val variables: List<VariablePayload>,
        val contextRequirement: ContextRequirement?,
        val state: String,
    )

    data class VariablePayload(
        val name: String,
        val type: String,
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
                            variables =
                                version.variables.map { variable ->
                                    VariablePayload(
                                        name = variable.name,
                                        type = variable.type.name,
                                        required = variable.required,
                                        default = variable.default,
                                        constraints = variable.constraints,
                                        sensitive = variable.sensitive,
                                    )
                                },
                            contextRequirement = version.contextRequirement,
                            state = version.state.toDbValue(),
                        )
                    },
            )
    }
}
