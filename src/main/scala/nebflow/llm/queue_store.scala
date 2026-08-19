package nebflow.llm

import cats.effect.IO
import io.circe.*
import io.circe.parser.decode
import io.circe.syntax.*
import nebflow.core.{AtomicJson, NebflowLogger, PathUtil}
import nebflow.shared.{LlmRequest, Message, ToolDefinition}
import nebflow.shared.given

/**
 * Persistent FIFO queue for concurrency-gated LLM requests (P0 API 并发管理,
 * design §4.5). When a request cannot get a gate permit immediately it is
 * written here so a restart doesn't lose it: on startup GatewayMain loads all
 * provider queues and re-drains them through the gate in order.
 *
 * Storage: ~/.nebflow/llm-queue/<providerId>.json  (JSON array of QueueItem)
 * Write: atomic (tmp + move), same pattern as MailQueueStore / TurnStateStore.
 * Corruption tolerance: unreadable files decode to empty (fresh start).
 *
 * Semantics: at-least-once. An item is removed when its permit is granted
 * (removeHead — FIFO guarantees the granted item is at head) or when its
 * acquire times out (removeById — the request has fallen through to the next
 * provider). A crash between grant and removal leaves a stale entry that
 * re-executes on restart — harmless for LLM calls (idempotent re-prompt).
 */
object LlmQueueStore:
  private val logger = NebflowLogger.forName("nebflow.llm.queue")

  case class QueueItem(
    id: String,
    providerId: String,
    request: LlmRequest,
    enqueuedAt: Long
  )

  // ---- LlmRequest codec (Message has a codec in shared/protocol.scala;
  // ToolDefinition/AgentModelConfig do not — hand-written readers here). ----

  private given Encoder[ToolDefinition] = Encoder.instance { t =>
    Json.obj("name" -> t.name.asJson, "description" -> t.description.asJson, "inputSchema" -> t.inputSchema.asJson)
  }
  private given Decoder[ToolDefinition] = Decoder.instance { c =>
    for
      name <- c.downField("name").as[String]
      description <- c.downField("description").as[String]
      inputSchema <- c.downField("inputSchema").as[Option[JsonObject]].map(_.getOrElse(JsonObject.empty))
    yield ToolDefinition(name, description, inputSchema)
  }

  private given Encoder[LlmRequest] = Encoder.instance { r =>
    Json.obj(
      "messages" -> r.messages.asJson,
      "sessionId" -> r.sessionId.asJson,
      "agentId" -> r.agentId.asJson,
      "tools" -> r.tools.asJson,
      "maxTokens" -> r.maxTokens.asJson,
      "thinking" -> r.thinking.asJson,
      "systemStable" -> r.systemStable.asJson,
      "systemDynamic" -> r.systemDynamic.asJson,
      "agentModel" -> r.agentModel.asJson
    )
  }

  private given Decoder[LlmRequest] = Decoder.instance { c =>
    // Note: Option fields use `=` bindings — a `<-` generator unwraps the
    // Option (Some(v) -> v), but LlmRequest wants the Option itself.
    for
      messages <- c.downField("messages").as[List[Message]].orElse(Right(Nil))
      sessionId <- c.downField("sessionId").as[String].orElse(Right(""))
      agentId <- c.downField("agentId").as[String].orElse(Right(""))
      tools = c.downField("tools").as[Option[List[ToolDefinition]]].toOption.flatten
      maxTokens = c.downField("maxTokens").as[Option[Int]].toOption.flatten
      thinking = c.downField("thinking").as[Option[Json]].toOption.flatten
      systemStable = c.downField("systemStable").as[Option[String]].toOption.flatten
      systemDynamic = c.downField("systemDynamic").as[Option[String]].toOption.flatten
      agentModel = c.downField("agentModel").as[Option[nebflow.shared.AgentModelConfig]].toOption.flatten
    yield LlmRequest(messages, sessionId, agentId, tools, maxTokens, thinking, systemStable, systemDynamic, agentModel)
  }

  private given Encoder[QueueItem] = Encoder.instance { i =>
    Json.obj("id" -> i.id.asJson, "providerId" -> i.providerId.asJson, "request" -> i.request.asJson, "enqueuedAt" -> i.enqueuedAt.asJson)
  }

  private given Decoder[QueueItem] = Decoder.instance { c =>
    for
      id <- c.downField("id").as[String].orElse(Right(""))
      providerId <- c.downField("providerId").as[String].orElse(Right(""))
      request <- c.downField("request").as[LlmRequest].orElse(Right(LlmRequest(Nil, "", "")))
      enqueuedAt <- c.downField("enqueuedAt").as[Option[Long]].map(_.getOrElse(0L))
    yield QueueItem(id, providerId, request, enqueuedAt)
  }

  private def queueDir: os.Path = PathUtil.dataRoot / "llm-queue"

  private def queueFile(providerId: String): os.Path = queueDir / s"$providerId.json"

  private def writeAll(providerId: String, items: List[QueueItem]): Unit =
    AtomicJson.writeSync(queueFile(providerId), items.asJson.noSpaces)

  private def readAll(providerId: String): List[QueueItem] =
    val file = queueFile(providerId)
    if !os.exists(file) then Nil
    else
      decode[List[QueueItem]](os.read(file)) match
        case Right(items) => items
        case Left(e) =>
          logger.warn(s"LlmQueueStore.load: failed to decode for $providerId: ${e.getMessage}")
          Nil

  /** Append an item to the end of the provider's queue. */
  def append(providerId: String, item: QueueItem): IO[Unit] =
    IO.blocking {
      val current = readAll(providerId)
      writeAll(providerId, current :+ item)
    }.void
      .handleErrorWith(e => logger.warn(s"LlmQueueStore.append failed for $providerId: ${e.getMessage}").void)

  /** Load all queued items. Empty for missing/corrupt files. */
  def load(providerId: String): IO[List[QueueItem]] =
    IO.blocking(readAll(providerId))
      .handleErrorWith(e => logger.warn(s"LlmQueueStore.load failed for $providerId: ${e.getMessage}").as(Nil))

  /** Remove and return the head item (the granted one — FIFO). */
  def removeHead(providerId: String): IO[Option[QueueItem]] =
    IO.blocking {
      readAll(providerId) match
        case head :: rest =>
          writeAll(providerId, rest)
          Some(head)
        case Nil => None
    }.handleErrorWith(e => logger.warn(s"LlmQueueStore.removeHead failed for $providerId: ${e.getMessage}").as(None))

  /** Remove a specific item by id (timeout cleanup). Returns the remaining items. */
  def removeById(providerId: String, id: String): IO[List[QueueItem]] =
    IO.blocking {
      val remaining = readAll(providerId).filterNot(_.id == id)
      writeAll(providerId, remaining)
      remaining
    }.handleErrorWith(e => logger.warn(s"LlmQueueStore.removeById failed for $providerId: ${e.getMessage}").as(Nil))

  /** Empty the provider's queue (startup replay claims all items before re-firing). */
  def clear(providerId: String): IO[Unit] =
    IO.blocking(writeAll(providerId, Nil)).void
      .handleErrorWith(e => logger.warn(s"LlmQueueStore.clear failed for $providerId: ${e.getMessage}").void)

  /** Queue length for a provider. */
  def size(providerId: String): IO[Int] =
    load(providerId).map(_.length)

  /** All provider ids with a non-empty queue file (startup recovery). */
  def providersWithQueues: IO[List[String]] =
    IO.blocking {
      val dir = queueDir
      if !os.exists(dir) then Nil
      else
        os.list(dir)
          .filter(_.last.endsWith(".json"))
          .map(_.baseName)
          .toList
    }.handleErrorWith(e => logger.warn(s"LlmQueueStore.providersWithQueues failed: ${e.getMessage}").as(Nil))

end LlmQueueStore
