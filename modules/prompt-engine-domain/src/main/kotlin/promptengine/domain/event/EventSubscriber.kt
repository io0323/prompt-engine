package promptengine.domain.event

/**
 * Brokerの1つ以上の[EventTopic]を購読し、届いた[EventEnvelope]を処理する購読側の契約
 * （設計書§14「購読先」列、ADR-0026決定1）。
 *
 * 実際のBroker接続・ポーリングループは`prompt-engine-infrastructure`の駆動側
 * （`KafkaSubscriberRunner`）が持ち、本Interfaceの実装はイベント1件の処理だけに責務を絞る。
 * これにより購読側のロジックはBrokerクライアントを知らずに単体テストできる。
 *
 * ## 冪等性（ADR-0025決定8）
 * Outbox + Brokerの配信はat-least-onceであり、同じ[EventEnvelope]が2回以上[handle]へ
 * 渡されうる。実装は自身の書き込み先テーブルの`event_id`一意制約と
 * `INSERT ... ON CONFLICT DO NOTHING`で重複を吸収する契約とする（共有の重複排除テーブルは
 * 持たない）。
 *
 * ## 失敗の扱い
 * [handle]が例外を投げた場合、駆動側はその例外を捕捉してDLQへ退避し、ポーリングループ自体は
 * 継続する（1件の失敗が購読全体を止めない、ADR-0026決定2）。
 */
interface EventSubscriber {
    /**
     * この購読側の識別名。Brokerのconsumer group ID、およびDLQ行の`subscriber_name`として
     * 使うため、購読側ごとに一意で安定した値であること（変更するとBroker側のオフセットが
     * リセットされ、過去のイベントを再消費する）。
     */
    val name: String

    /** 購読対象のTopic集合。 */
    val topics: Set<EventTopic>

    /**
     * [envelope]を処理する。関心の無いイベント種別（[EventEnvelope.eventType]）は
     * 何もせず戻ってよい。処理に失敗した場合は例外を投げる（駆動側がDLQへ退避する）。
     */
    fun handle(envelope: EventEnvelope)
}
