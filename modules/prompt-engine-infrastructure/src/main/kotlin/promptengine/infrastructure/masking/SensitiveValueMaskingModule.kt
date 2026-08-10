package promptengine.infrastructure.masking

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.module.SimpleModule
import promptengine.domain.shared.SensitiveValue

/**
 * [SensitiveValue]を常に`"***"`としてシリアライズするJacksonモジュール（ADR-0026決定4、
 * Secretマスクの第1層）。
 *
 * CLAUDE.md「Secret / sensitive=trueの変数値は絶対に出力しない。マスク処理は
 * `SensitiveValue`型に閉じ込め、`toString()`は常に`"***"`を返す」を、JSONシリアライズ経路にも
 * 及ぼす。[SensitiveValue.toString]のマスクだけでは、Jacksonがフィールドを直接読む経路
 * （可視性設定次第で`private val raw`にも到達しうる）を塞げないため、型に対する明示的な
 * Serializerとして固定する。
 *
 * `prompt-engine-bootstrap`がアプリケーション全体の`ObjectMapper`へ登録する。Outbox
 * （`event_bus_outbox.payload`・`domain_events.payload`）→ Broker → `audit_logs.payload`という
 * 経路の**入口**でマスクされるため、下流の購読側は既にマスク済みのJSONを受け取る。
 */
class SensitiveValueMaskingModule : SimpleModule(MODULE_NAME) {
    init {
        addSerializer(SensitiveValue::class.java, SensitiveValueSerializer())
    }

    private class SensitiveValueSerializer : JsonSerializer<SensitiveValue>() {
        override fun serialize(
            value: SensitiveValue,
            gen: JsonGenerator,
            serializers: SerializerProvider,
        ) {
            gen.writeString(MASK)
        }
    }

    companion object {
        private const val MODULE_NAME = "SensitiveValueMaskingModule"

        /** [SensitiveValue.toString]と同じマスク文字列。 */
        const val MASK = "***"
    }
}
