package promptengine.infrastructure.persistence

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import promptengine.domain.shared.SemVer
import java.util.UUID

/**
 * 業務キー（`prompt_key` + SemVer）から`prompt_versions.version_id`（永続化サロゲートUUID）を
 * 解決する（ADR-0026決定1）。
 *
 * Brokerを流れるイベントは業務キーしか運ばない（ADR-0025決定1）が、`execution_logs`・
 * `evaluation_records`のFKはサロゲートキーであるため、購読側の書き込み時にこの変換が要る。
 * `domain_events.aggregate_id`・`audit_logs.aggregate_id`が`prompts.prompt_id`へ解決するのと
 * 同じ方針（V1マイグレーションのコメント参照）。
 */
internal fun NamedParameterJdbcTemplate.findVersionId(
    promptKey: String,
    semVer: SemVer,
): UUID? =
    query(
        """
        SELECT pv.version_id
        FROM prompt_versions pv
        JOIN prompts p ON p.prompt_id = pv.prompt_id
        WHERE p.prompt_key = :promptKey AND pv.version = :version
        """.trimIndent(),
        MapSqlParameterSource()
            .addValue("promptKey", promptKey)
            .addValue("version", semVer.toString()),
    ) { rs, _ -> rs.getObject("version_id", UUID::class.java) }
        .singleOrNull()

/**
 * [findVersionId]の解決に失敗した場合に投げる。購読側はこれを捕捉してDLQへ退避する
 * （イベントは届いたがPrompt本体が既に削除されている等、再処理では解決しない状態のため
 * 無限リトライさせない、ADR-0026決定2）。
 */
internal class PromptVersionNotResolvableException(promptKey: String, semVer: SemVer) :
    IllegalStateException("prompt version not found for promptKey='$promptKey' version='$semVer'")
