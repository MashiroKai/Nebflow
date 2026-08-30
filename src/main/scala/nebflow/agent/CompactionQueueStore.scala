package nebflow.agent

import nebflow.core.AtomicJson
import nebflow.core.NebflowLogger
import nebflow.core.PathUtil
import nebflow.shared.ContentBlock
import nebflow.shared.given // ContentBlock Encoder/Decoder (protocol.scala top-level)
import cats.effect.IO
import io.circe.*
import io.circe.parser.decode
import io.circe.syntax.*

/**
 * F2 (2026-08-30, compact-injection-shield batch 2): durable queue store for
 * the compaction-window injection queues.
 *
 * `pendingImmediateInputs` and `pendingEvents` are pure in-memory lists in
 * ExecutionContext (protocol.scala:872/:885 "In-memory only"). A crash or
 * SIGKILL mid-compaction loses every injection that was queued during the
 * window — the exact failure this batch exists to close (G2). This store
 * snapshots both queues to disk on every enqueue / drain, and the agent
 * replays them at spawn (RecoverPersistedQueues), so a hard kill degrades to
 * "queued injections survive the restart and land in the next turn" instead
 * of "silently lost".
 *
 * Storage: ~/.nebflow/sessions/<sessionId>/injection-queues.json
 * Write: atomic (tmp + move), same pattern as TurnStateStore / MailQueueStore.
 * `pendingUserInputs` is explicitly NOT persisted (batch-2 scope): the WS
 * layer already keeps the user's text in appendUiMessages, so a crash there
 * degrades to the pre-existing frontend-visible state.
 */
object CompactionQueueStore:
  private val logger = NebflowLogger.forName("nebflow.agent.queuepersist")

  /** Disk snapshot of the two persisted queues. */
  case class PersistedQueues(
    imms: List[AgentCommand.ImmediateInput] = Nil,
    events: List[AgentCommand.ExternalEvent] = Nil
  )

  // ── Codecs (hand-written, few fields; ContentBlock codec reused) ──

  given Encoder[AgentCommand.ImmediateInput] = Encoder.instance { imm =>
    val base = Json.obj("text" -> imm.text.asJson)
    val withBlocks = imm.blocks.fold(base)(b => base.deepMerge(Json.obj("blocks" -> b.asJson)))
    imm.source.fold(withBlocks)(v => withBlocks.deepMerge(Json.obj("source" -> v.asJson)))
      .deepMerge(imm.eventType.fold(Json.obj())(v => Json.obj("eventType" -> v.asJson)))
      .deepMerge(imm.sender.fold(Json.obj())(v => Json.obj("sender" -> v.asJson)))
      .deepMerge(imm.senderTeam.fold(Json.obj())(v => Json.obj("senderTeam" -> v.asJson)))
      .deepMerge(imm.delivery.fold(Json.obj())(v => Json.obj("delivery" -> v.asJson)))
  }

  given Decoder[AgentCommand.ImmediateInput] = Decoder.instance { c =>
    for
      text <- c.downField("text").as[String]
      blocks <- c.downField("blocks").as[Option[List[ContentBlock]]]
      source <- c.downField("source").as[Option[String]]
      eventType <- c.downField("eventType").as[Option[String]]
      sender <- c.downField("sender").as[Option[String]]
      senderTeam <- c.downField("senderTeam").as[Option[String]]
      delivery <- c.downField("delivery").as[Option[String]]
    yield AgentCommand.ImmediateInput(text, blocks, source, eventType, sender, senderTeam, delivery)
  }

  given Encoder[AgentCommand.ExternalEvent] = Encoder.instance { e =>
    Json.obj(
      "source" -> e.source.asJson,
      "eventType" -> e.eventType.asJson,
      "payload" -> e.payload.asJson,
      "metadata" -> Json.fromJsonObject(e.metadata),
      "correlationId" -> e.correlationId.asJson
    )
  }

  given Decoder[AgentCommand.ExternalEvent] = Decoder.instance { c =>
    for
      source <- c.downField("source").as[String]
      eventType <- c.downField("eventType").as[String]
      payload <- c.downField("payload").as[String]
      metadata <- c.downField("metadata").as[Option[JsonObject]]
      correlationId <- c.downField("correlationId").as[Option[String]]
    yield AgentCommand.ExternalEvent(source, eventType, payload, metadata.getOrElse(JsonObject.empty), correlationId)
  }

  given Encoder[PersistedQueues] = Encoder.instance { q =>
    Json.obj("imms" -> q.imms.asJson, "events" -> q.events.asJson)
  }

  given Decoder[PersistedQueues] = Decoder.instance { c =>
    for
      imms <- c.downField("imms").as[Option[List[AgentCommand.ImmediateInput]]]
      events <- c.downField("events").as[Option[List[AgentCommand.ExternalEvent]]]
    yield PersistedQueues(imms.getOrElse(Nil), events.getOrElse(Nil))
  }

  private def queueFile(sessionId: String): os.Path =
    PathUtil.dataRoot / "sessions" / sessionId / "injection-queues.json"

  /**
   * Persist the current queue snapshot. Empty queues delete the file (a
   * crash with nothing queued must not resurrect stale entries). No-op for
   * empty sessionId. Failures are logged, never propagated — enqueue paths
   * must not be blocked by a storage hiccup.
   */
  def save(sessionId: String, q: PersistedQueues): IO[Unit] =
    if sessionId.isEmpty then IO.unit
    else if q.imms.isEmpty && q.events.isEmpty then clear(sessionId)
    else
      val file = queueFile(sessionId)
      IO.blocking {
        if !os.exists(file / os.up) then os.makeDir.all(file / os.up)
        AtomicJson.writeSync(file, q.asJson.noSpaces)
      }.void
        .handleErrorWith(e => logger.warn(s"CompactionQueueStore.save failed for $sessionId: ${e.getMessage}").void)

  /** Load the persisted queues. None if missing or corrupt (corrupt → warn + None). */
  def load(sessionId: String): IO[Option[PersistedQueues]] =
    if sessionId.isEmpty then IO.pure(None)
    else
      val file = queueFile(sessionId)
      IO.blocking {
        if !os.exists(file) then None
        else
          decode[PersistedQueues](os.read(file)) match
            case Right(q) => Some(q)
            case Left(e) =>
              logger.warn(s"Failed to decode injection-queues for $sessionId: ${e.getMessage}")
              None
      }

  /** Delete the queue file (both queues empty). */
  def clear(sessionId: String): IO[Unit] =
    if sessionId.isEmpty then IO.unit
    else
      val file = queueFile(sessionId)
      IO.blocking {
        if os.exists(file) then os.remove(file)
      }.void
        .handleErrorWith(e => logger.warn(s"CompactionQueueStore.clear failed for $sessionId: ${e.getMessage}").void)

end CompactionQueueStore
