package promptengine.infrastructure.messaging

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
     */
    fun send(
        topic: String,
        key: String,
        value: String,
    )
}
