package nebflow.db.format

import io.circe.syntax.*
import io.circe.{Decoder, Encoder, Json}

import java.security.MessageDigest

/**
 * 事件流存储 envelope（设计 §3.4(a) + D-12 对齐令）。
 *
 * 分层（D-12 工作默认）：
 *  - **外层 = 存储 envelope**（本库所有）：`seq`（全局单调号）/ `checksum`（内核事件
 *    canonical 序列化的 sha256）/ `dedupKey`（重放去重，〔提案〕，可选）。
 *  - **内层 `event` = 内核事件 envelope 原样不动的 Json**：字段命名权归 pi-logic 线
 *    （最小形状〈event-type, occurred-at-epoch-millis, session-id, seq-in-session,
 *    payload-json〉，以其方案 §Ⅱ 命名为准）——本库不改内核字段，结构上以 opaque Json
 *    保证。
 */
final case class EventEnvelope(seq: Long, checksum: String, dedupKey: Option[String], event: Json)

object EventEnvelope:

  /**
   * 校验和 = 内核事件 canonical 序列化（`noSpaces`）的 sha256。写读同径（circe
   * parse→noSpaces 圆回一致）⇒ 读面重算可比对；不符 = 帧损坏（读面升级 Corrupt）。
   */
  def checksumOf(event: Json): String =
    val d = MessageDigest.getInstance("SHA-256").digest(event.noSpaces.getBytes("UTF-8"))
    "sha256:" + d.map(b => "%02x".format(b)).mkString

  /** 发号后包装（写面单点：checksum 只在此计算）。 */
  def wrap(seq: Long, event: Json, dedupKey: Option[String]): EventEnvelope =
    EventEnvelope(seq, checksumOf(event), dedupKey, event)

  /** 完整性校验（读面）。 */
  def verify(e: EventEnvelope): Boolean = e.checksum == checksumOf(e.event)

  given Encoder[EventEnvelope] = e =>
    Json.obj(
      "seq" -> Json.fromLong(e.seq),
      "checksum" -> Json.fromString(e.checksum),
      "dedupKey" -> e.dedupKey.asJson, // None → null（落库样例对拍形）
      "event" -> e.event
    )

  given Decoder[EventEnvelope] = c =>
    for
      seq <- c.downField("seq").as[Long]
      checksum <- c.downField("checksum").as[String]
      dedup <- c.downField("dedupKey").as[Option[String]]
      event <- c.downField("event").as[Json]
    yield EventEnvelope(seq, checksum, dedup, event)

end EventEnvelope
