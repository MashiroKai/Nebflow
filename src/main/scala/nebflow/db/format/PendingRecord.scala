package nebflow.db.format

import io.circe.{Decoder, Encoder, Json}
import io.circe.syntax.*

import java.security.MessageDigest

/**
 * 待审单记录（设计 §3.4(c)，案 B 类③）：内置权限单 + 待答 ask / MCP 权限单。
 *
 * 现状 7 字段（`PendingAskLedger.Entry` 派生）保留；〔提案〕新增 6：`seq`、
 * `payloadDigest`（大 payload 不全文入库——只落摘要）、`state`、`closedAt`、`closedBy`、
 * `closeReason`。**闭合 = 追加决议事件行，不改写原行**（对齐 triple D-20；现状
 * `remove` 是删行——tombstone 式闭合取而代之）。
 *
 * 不可再生面（三标签①）：坏 ⇒ 隔离 + 显式拒绝。
 */
final case class PendingRecord(
  seq: Long,
  requestId: String,
  kind: String, // "Permission" | "McpPermission" | "AskUser"
  rootSessionId: String,
  sourceSession: String,
  sourceAgent: String,
  firstQuestion: String,
  payloadDigest: String,
  createdAt: Long,
  state: String, // "open" | "closed"
  closedAt: Option[Long],
  closedBy: Option[String],
  closeReason: Option[String]
)

object PendingRecord:

  val KindPermission: String = "Permission"
  val KindMcpPermission: String = "McpPermission"
  val KindAskUser: String = "AskUser"

  val StateOpen: String = "open"
  val StateClosed: String = "closed"

  /** 开单构造入参（seq 由库发号；payload 只入摘要不入全文）。 */
  final case class PendingCard(
    requestId: String,
    kind: String,
    rootSessionId: String,
    sourceSession: String,
    sourceAgent: String,
    firstQuestion: String,
    payload: Json
  )

  def digestOf(payload: Json): String =
    val d = MessageDigest.getInstance("SHA-256").digest(payload.noSpaces.getBytes("UTF-8"))
    "sha256:" + d.map(b => "%02x".format(b)).mkString

  /** 开单行（写面单点）。 */
  def openRow(seq: Long, card: PendingCard, createdAt: Long): PendingRecord =
    PendingRecord(
      seq = seq,
      requestId = card.requestId,
      kind = card.kind,
      rootSessionId = card.rootSessionId,
      sourceSession = card.sourceSession,
      sourceAgent = card.sourceAgent,
      firstQuestion = card.firstQuestion,
      payloadDigest = digestOf(card.payload),
      createdAt = createdAt,
      state = StateOpen,
      closedAt = None,
      closedBy = None,
      closeReason = None
    )

  /** 闭单行（追加的决议事件：同 requestId 新行，state=closed——原行字节不动）。 */
  def closeRow(base: PendingRecord, seq: Long, closedAt: Long, closedBy: String, closeReason: String): PendingRecord =
    base.copy(
      seq = seq,
      state = StateClosed,
      closedAt = Some(closedAt),
      closedBy = Some(closedBy),
      closeReason = Some(closeReason)
    )

  // 字段序 = 设计 §3.4(c) 样例序（对拍面）。
  given Encoder[PendingRecord] = r =>
    Json.obj(
      "seq" -> Json.fromLong(r.seq),
      "requestId" -> Json.fromString(r.requestId),
      "kind" -> Json.fromString(r.kind),
      "rootSessionId" -> Json.fromString(r.rootSessionId),
      "sourceSession" -> Json.fromString(r.sourceSession),
      "sourceAgent" -> Json.fromString(r.sourceAgent),
      "firstQuestion" -> Json.fromString(r.firstQuestion),
      "payloadDigest" -> Json.fromString(r.payloadDigest),
      "createdAt" -> Json.fromLong(r.createdAt),
      "state" -> Json.fromString(r.state),
      "closedAt" -> r.closedAt.asJson,
      "closedBy" -> r.closedBy.asJson,
      "closeReason" -> r.closeReason.asJson
    )

  given Decoder[PendingRecord] = c =>
    for
      seq <- c.downField("seq").as[Long]
      requestId <- c.downField("requestId").as[String]
      kind <- c.downField("kind").as[String]
      root <- c.downField("rootSessionId").as[String]
      source <- c.downField("sourceSession").as[String]
      agent <- c.downField("sourceAgent").as[String]
      question <- c.downField("firstQuestion").as[String]
      digest <- c.downField("payloadDigest").as[String]
      createdAt <- c.downField("createdAt").as[Long]
      state <- c.downField("state").as[String]
      closedAt <- c.downField("closedAt").as[Option[Long]]
      closedBy <- c.downField("closedBy").as[Option[String]]
      closeReason <- c.downField("closeReason").as[Option[String]]
    yield PendingRecord(
      seq,
      requestId,
      kind,
      root,
      source,
      agent,
      question,
      digest,
      createdAt,
      state,
      closedAt,
      closedBy,
      closeReason
    )

end PendingRecord
