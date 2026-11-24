package nebflow.pilogic

import io.circe.syntax.*
import io.circe.{Decoder, Encoder, HCursor, Json}

/**
 * 内核事件 envelope——全项目事件的最小统一形状（全项目等它对拍）。
 *
 * 对拍锚：db 线 D-12（`docs/20261104_135608_scala-db-module-impl-v1__delegate-nebflow-ebb64e95.md` §5）——
 * db 存储层的外层存储 envelope `{seq, checksum, dedupKey}` 包本 envelope 原样存于内层 `event` 字段；
 * wire 字段名与该节落库样例逐字对齐：
 *
 *   - `eventType`   ↔ `"type"`          事件类型名（如 "node-failed"）
 *   - `occurredAt`  ↔ `"occurredAt"`    发生时刻 epoch millis（纯值：不带时钟，时钟归装配层）
 *   - `sessionId`   ↔ `"sessionId"`     会话标识
 *   - `seqInSession` ↔ `"seqInSession"` 会话内单调序号（从 1 起）
 *   - `payload`     ↔ `"payload"`       载荷 JSON（原样透传，本模块不解释）
 *
 * 设计裁量（本轮定稿，接线期对拍以此为准）：
 *   - db 样例内层另有 `"subject"` 字段——本 envelope 不设独立字段，归属 payload；
 *     Decoder 容忍未知字段（db 线已按占位名落库的样例行可直接解码），Encoder 恒出且仅出上述五字段。
 */
final case class KernelEvent(
  eventType: String,
  occurredAt: Long,
  sessionId: String,
  seqInSession: Long,
  payload: Json
)

object KernelEvent:

  given Encoder[KernelEvent] =
    (e: KernelEvent) =>
      Json.obj(
        "type" -> e.eventType.asJson,
        "occurredAt" -> e.occurredAt.asJson,
        "sessionId" -> e.sessionId.asJson,
        "seqInSession" -> e.seqInSession.asJson,
        "payload" -> e.payload
      )

  given Decoder[KernelEvent] =
    (c: HCursor) =>
      for
        eventType <- c.get[String]("type")
        occurredAt <- c.get[Long]("occurredAt")
        sessionId <- c.get[String]("sessionId")
        seqInSession <- c.get[Long]("seqInSession")
        payload <- c.get[Json]("payload")
      yield KernelEvent(eventType, occurredAt, sessionId, seqInSession, payload)

  /** db §D-12 样例内核形状（2026-11-04 落库形态，subject 已归 payload 故不在）——编码回归锚，勿随手改。 */
  val dbSampleWire: String =
    """{"type":"node-failed","occurredAt":1762224000001,"sessionId":"s-1","seqInSession":1,"payload":{"summary":"sample-1","chainId":null}}"""
end KernelEvent
