package promptengine.infrastructure.messaging

import java.util.UUID

/**
 * [OutboxRelayer]がBrokerへ1件送信するための抽象（ADR-0025決定3フェーズ2）。
 * テスト（[OutboxRelayer]の単体テスト）では実Brokerを使わずモック/フェイクで検証できるよう、
 * `org.apache.kafka.clients.producer.Producer`を直接扱わずこの薄いインターフェースに包む。
 */
fun interface EventProducer {
    /**
     * [topic]へ[key]をパーティションキーとして[value]を送信する。送信が確認できるまで
     * ブロックする契約（[OutboxRelayer]は同期送信を前提に成功/失敗を判定する）。
     * 送信に失敗した場合は例外を投げる。
     *
     * [eventId]はBrokerメッセージのヘッダ（実装依存）としても運ぶ。購読側が
     * `event_id UNIQUE`による重複排除（ADR-0025決定8）を行う際、[value]（payload）を
     * 逆シリアライズせずヘッダだけで判定できるようにするため。
     */
    fun send(
        topic: String,
        key: String,
        value: String,
        eventId: UUID,
    )
}
