package nebflow.db.format

import io.circe.{Decoder, Encoder, Json}
import io.circe.syntax.*

/**
 * 会话消息记录 envelope（设计 §3.4(b)/(e)，案 B 类①）。
 *
 * envelope 6 字段〔提案〕；`payload` 零改动——现状 `Message`/`UiMessage` JSON 原样
 * （opaque Json）。`stream ∈ llm|ui`（文件面 = messages / ui 两个分片族）。
 * 压缩点 = `kind:"compact-point"` 行 + `snapshots/<n>.json` 引用（payload =
 * [[CompactPoint]] 形）。
 */
final case class SessionEnvelope(
  seq: Long,
  ts: Long,
  sessionId: String,
  stream: String, // "llm" | "ui"
  kind: String,   // "message.append" | "compact-point"
  payload: Json
)

object SessionEnvelope:

  val KindMessageAppend: String = "message.append"
  val KindCompactPoint: String = "compact-point"

  val StreamLlm: String = "llm"
  val StreamUi: String = "ui"

  /** 压缩点载荷（设计 §3.4(e)）：〔提案〕新增 seq/kind/ts/tokens 4 字段中的载荷段。 */
  final case class CompactPoint(beforeRef: String, afterRef: String, tokens: Long)

  object CompactPoint:
    given Encoder[CompactPoint] = cp =>
      Json.obj(
        "beforeRef" -> Json.fromString(cp.beforeRef),
        "afterRef" -> Json.fromString(cp.afterRef),
        "tokens" -> Json.fromLong(cp.tokens)
      )
    given Decoder[CompactPoint] = c =>
      for
        before <- c.downField("beforeRef").as[String]
        after <- c.downField("afterRef").as[String]
        tokens <- c.downField("tokens").as[Long]
      yield CompactPoint(before, after, tokens)

  given Encoder[SessionEnvelope] = e =>
    Json.obj(
      "seq" -> Json.fromLong(e.seq),
      "ts" -> Json.fromLong(e.ts),
      "sessionId" -> Json.fromString(e.sessionId),
      "stream" -> Json.fromString(e.stream),
      "kind" -> Json.fromString(e.kind),
      "payload" -> e.payload
    )

  given Decoder[SessionEnvelope] = c =>
    for
      seq <- c.downField("seq").as[Long]
      ts <- c.downField("ts").as[Long]
      sessionId <- c.downField("sessionId").as[String]
      stream <- c.downField("stream").as[String]
      kind <- c.downField("kind").as[String]
      payload <- c.downField("payload").as[Json]
    yield SessionEnvelope(seq, ts, sessionId, stream, kind, payload)

end SessionEnvelope
