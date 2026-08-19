package nebflow.core.flow

import nebflow.core.AtomicJson
import cats.effect.IO
import io.circe.*
import io.circe.parser.decode
import io.circe.syntax.*
import nebflow.core.{NebflowLogger, PathUtil}

/**
 * Persistent FIFO queue for delivery=queue mails.
 *
 * Queue mails are processed one at a time — each triggers a full turn, and
 * the next is only dequeued after the turn completes (back to idle). The
 * queue survives restart because items are written atomically to disk.
 *
 * Storage: ~/.nebflow/sessions/<sessionId>/mail-queue.json
 * Write: atomic (tmp + move), same pattern as TurnStateStore / FlowMailStore.
 */
object MailQueueStore:
  private val logger = NebflowLogger.forName("nebflow.flow.mailqueue")

  case class MailQueueItem(
    id: String,
    from: String,
    fromSession: String,
    message: String,
    /** Advisory type tag (INFO / RESULT etc.), same vocabulary as MailTool type. */
    `type`: String,
    timestamp: Long,
    /**
     * G3: image attachment paths. The queue persists paths (not base64 — queue
     * files stay small); paths are re-read and re-compressed at drain time via
     * ImageInject.drainImagePaths. A file that vanished between send and drain
     * degrades to an `[attachment lost: path]` placeholder.
     */
    imagePaths: List[String] = Nil
  )

  given Encoder[MailQueueItem] = Encoder.instance { item =>
    Json.obj(
      "id" -> item.id.asJson,
      "from" -> item.from.asJson,
      "fromSession" -> item.fromSession.asJson,
      "message" -> item.message.asJson,
      "type" -> item.`type`.asJson,
      "timestamp" -> item.timestamp.asJson,
      // G3 attachment paths — old decoders ignore unknown fields (hand-written
      // downField readers), so this is forward compatible.
      "imagePaths" -> item.imagePaths.asJson
    )
  }

  given Decoder[MailQueueItem] = Decoder.instance { c =>
    for
      id <- c.downField("id").as[String]
      from <- c.downField("from").as[String]
      fromSession <- c.downField("fromSession").as[String].orElse(Right(""))
      message <- c.downField("message").as[String]
      itemType <- c.downField("type").as[String].orElse(Right("INFO"))
      timestamp <- c.downField("timestamp").as[Option[Long]].map(_.getOrElse(0L))
      imagePaths <- c.downField("imagePaths").as[List[String]].orElse(Right(Nil))
    yield MailQueueItem(id, from, fromSession, message, itemType, timestamp, imagePaths)
  }

  private def sessionDir(sessionId: String): os.Path =
    PathUtil.dataRoot / "sessions" / sessionId

  private def queueFile(sessionId: String): os.Path =
    sessionDir(sessionId) / "mail-queue.json"

  /** Append an item to the end of the queue. No-op for empty sessionId. */
  def append(sessionId: String, item: MailQueueItem): IO[Unit] =
    if sessionId.isEmpty then IO.unit
    else
      val file = queueFile(sessionId)
      val dir = file / os.up
      IO.blocking {
        if !os.exists(dir) then os.makeDir.all(dir)
        val current =
          if os.exists(file) then
            decode[List[MailQueueItem]](os.read(file)) match
              case Right(items) => items
              case Left(_)      => Nil // corrupt file — start fresh
          else Nil
        val updated = current :+ item
        AtomicJson.writeSync(file, updated.asJson.noSpaces)
      }.void
        .handleErrorWith(e => logger.warn(s"MailQueueStore.append failed for $sessionId: ${e.getMessage}").void)

  /** Load all queue items. Returns empty list for missing/corrupt files. */
  def load(sessionId: String): IO[List[MailQueueItem]] =
    if sessionId.isEmpty then IO.pure(Nil)
    else
      val file = queueFile(sessionId)
      IO.blocking {
        if !os.exists(file) then Nil
        else
          decode[List[MailQueueItem]](os.read(file)) match
            case Right(items) => items
            case Left(e) =>
              logger.warn(s"MailQueueStore.load: failed to decode for $sessionId: ${e.getMessage}")
              Nil
      }

  /** Remove and return the head item. Returns None if queue is empty. */
  def removeHead(sessionId: String): IO[Option[MailQueueItem]] =
    if sessionId.isEmpty then IO.pure(None)
    else
      val file = queueFile(sessionId)
      val dir = file / os.up
      IO.blocking {
        if !os.exists(file) then None
        else
          decode[List[MailQueueItem]](os.read(file)) match
            case Right(head :: rest) =>
              AtomicJson.writeSync(file, rest.asJson.noSpaces)
              Some(head)
            case Right(Nil) => None
            case Left(_) => None
      }.handleErrorWith(e => logger.warn(s"MailQueueStore.removeHead failed for $sessionId: ${e.getMessage}").as(None))

  /** Remove a specific item by id. Returns the remaining items. */
  def removeById(sessionId: String, id: String): IO[List[MailQueueItem]] =
    if sessionId.isEmpty then IO.pure(Nil)
    else
      val file = queueFile(sessionId)
      val dir = file / os.up
      IO.blocking {
        if !os.exists(file) then Nil
        else
          decode[List[MailQueueItem]](os.read(file)) match
            case Right(items) =>
              val remaining = items.filterNot(_.id == id)
              AtomicJson.writeSync(file, remaining.asJson.noSpaces)
              remaining
            case Left(_) => Nil
      }.handleErrorWith(e => logger.warn(s"MailQueueStore.removeById failed for $sessionId: ${e.getMessage}").as(Nil))

  /** Return the queue length. */
  def size(sessionId: String): IO[Int] =
    load(sessionId).map(_.length)

end MailQueueStore
