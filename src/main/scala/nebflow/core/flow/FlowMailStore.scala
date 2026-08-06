package nebflow.core.flow

import cats.effect.IO
import io.circe.*
import io.circe.parser.decode
import io.circe.syntax.*
import nebflow.core.{NebflowLogger, PathUtil}

/**
 * Persistent mail history per flow instance.
 * Each Mail between flow agents is recorded so users can review communication.
 *
 * Storage: ~/.nebflow/sessions/<sessionId>/flow-mailbox/<flowName>.json
 */
object FlowMailStore:
  private val logger = NebflowLogger.forName("nebflow.flow.mailbox")

  case class MailRecord(
    from: String,
    to: String,
    message: String,
    timestamp: Long = System.currentTimeMillis()
  )

  given Encoder[MailRecord] = Encoder.instance { m =>
    Json.obj(
      "from" -> m.from.asJson,
      "to" -> m.to.asJson,
      "message" -> m.message.asJson,
      "timestamp" -> m.timestamp.asJson
    )
  }

  given Decoder[MailRecord] = Decoder.instance { c =>
    for
      from <- c.downField("from").as[String]
      to <- c.downField("to").as[String]
      message <- c.downField("message").as[String]
      timestamp <- c.downField("timestamp").as[Option[Long]]
    yield MailRecord(from, to, message, timestamp.getOrElse(0L))
  }

  private def mailboxDir(sessionId: String): os.Path =
    require(sessionId.matches("^[a-fA-F0-9-]{1,64}$"), s"Invalid sessionId: $sessionId")
    PathUtil.dataRoot / "sessions" / sessionId / "flow-mailbox"

  /** Sanitize flow name for use as a filename (project/pipeline names contain /). */
  private def safeFlowName(flowName: String): String =
    flowName.replace("/", "_")

  private def mailboxFile(sessionId: String, flowName: String): os.Path =
    val safe = safeFlowName(flowName)
    require(safe.matches("^[a-zA-Z0-9._-]{1,128}$"), s"Invalid flow name: $flowName")
    mailboxDir(sessionId) / s"$safe.json"

  /** Append a mail record (atomic read-modify-write). */
  def append(sessionId: String, flowName: String, record: MailRecord): IO[Unit] =
    if sessionId.isEmpty then IO.unit
    else
      val file = mailboxFile(sessionId, flowName)
      IO.blocking {
        val dir = file / os.up
        if !os.exists(dir) then os.makeDir.all(dir)
        val existing: List[MailRecord] =
          if os.exists(file) then
            decode[List[MailRecord]](os.read(file)) match
              case Right(records) => records
              case Left(_) => Nil
          else Nil
        val updated = (existing :+ record).takeRight(200) // cap at 200 entries
        val tmp = dir / s".${file.last}.tmp"
        os.write.over(tmp, updated.asJson.noSpaces)
        os.move.over(tmp, file, replaceExisting = true)
      }.void
        .handleErrorWith(e => logger.warn(s"FlowMailStore.append failed: ${e.getMessage}").void)

  /** Load all mail records for a flow instance. */
  def load(sessionId: String, flowName: String): IO[List[MailRecord]] =
    if sessionId.isEmpty then IO.pure(Nil)
    else
      val file = mailboxFile(sessionId, flowName)
      IO.blocking {
        if !os.exists(file) then Nil
        else
          decode[List[MailRecord]](os.read(file)) match
            case Right(records) => records
            case Left(e) =>
              logger.warn(s"Failed to decode mailbox for $flowName: ${e.getMessage}")
              Nil
      }

  /** Clear all mail records for a flow instance. */
  def clear(sessionId: String, flowName: String): IO[Unit] =
    if sessionId.isEmpty then IO.unit
    else
      val file = mailboxFile(sessionId, flowName)
      IO.blocking {
        if os.exists(file) then os.remove(file)
      }.void

end FlowMailStore
