package nebflow.core.telemetry

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.NebflowLogger

import java.util.UUID

/**
 * Lightweight telemetry reporter for Nebflow.
 *
 * Collects anonymous usage events and reports them in batches.
 * All I/O is non-blocking; failures are silently swallowed to never impact the user.
 *
 * Design:
 *   - client_id: random UUID, persisted in ~/.nebflow/client-id
 *   - Events are buffered in memory and flushed on demand (session end, app shutdown)
 *   - Offline cache: pending events written to ~/.nebflow/telemetry-queue.json
 *   - Opt-out: NEBFLOW_TELEMETRY=false env or telemetry: false in config
 */
class TelemetryReporter private (
  clientId: String,
  endpoint: String,
  appVersion: String,
  osName: String,
  queue: Ref[IO, List[TelemetryEvent]],
  sender: TelemetrySender
):

  private val logger = NebflowLogger.forName("nebflow.telemetry")

  // ---- Public API ----

  /** Record an event. Non-blocking, fire-and-forget. */
  def record(event: String, properties: JsonObject = JsonObject.empty): IO[Unit] =
    val ev = TelemetryEvent(event, System.currentTimeMillis(), properties)
    queue.update(ev :: _).handleErrorWith(_ => IO.unit)

  /** Flush all buffered events to the server. */
  def flush: IO[Unit] =
    queue.getAndSet(Nil).flatMap { events =>
      if events.isEmpty then IO.unit
      else
        val payload = TelemetryEvent.encodeBatch(events, clientId, appVersion, osName)
        sender.send(endpoint, payload).handleErrorWith(_ => IO.unit)
    }

  /** Flush + persist any unsent events for next startup. */
  def shutdown: IO[Unit] =
    flush.handleErrorWith(_ => IO.unit) *> persistQueue.handleErrorWith(_ => IO.unit)

  // ---- Internals ----

  private val queueFile = TelemetryReporter.dataRoot / "telemetry-queue.json"

  private def persistQueue: IO[Unit] =
    queue.get.flatMap { events =>
      if events.isEmpty then IO.unit
      else IO.blocking {
        val json = TelemetryEvent.encodeBatch(events, clientId, appVersion, osName)
        os.write.over(queueFile, json.noSpaces, createFolders = true)
      }
    }

end TelemetryReporter

object TelemetryReporter:

  private val logger = NebflowLogger.forName("nebflow.telemetry")

  private def dataRoot: os.Path = nebflow.core.PathUtil.dataRoot

  /** Default telemetry endpoint (CloudBase). */
  val DefaultEndpoint = "https://cloudbase-3gltu9is7f791a38.service.tcloudbase.com/nebflow-telemetry"

  /** Check if telemetry is enabled via env or config. */
  def isEnabled: Boolean =
    // Env var takes highest priority
    sys.env.get("NEBFLOW_TELEMETRY").map(_.toLowerCase) match
      case Some("false") | Some("0") | Some("no") => false
      case _ =>
        // Check config file
        try
          val configPath = dataRoot / "config.yaml"
          if os.exists(configPath) then
            val content = os.read(configPath)
            // Simple check — don't pull in a full YAML parser for this
            content.linesIterator.exists { line =>
              val trimmed = line.trim
              trimmed.startsWith("telemetry:") &&
                trimmed.substring("telemetry:".length).trim.toLowerCase == "false"
            } == false // if telemetry: false exists, return false
          else true
        catch
          case _: Exception => true

  /** Detect simplified OS name. */
  def detectOs: String =
    val osName = sys.props.getOrElse("os.name", "").toLowerCase
    if osName.contains("mac") then "macos"
    else if osName.contains("win") then "windows"
    else if osName.contains("linux") then "linux"
    else "other"

  /** Load or generate client_id. */
  private def loadOrCreateClientId: IO[String] =
    IO.blocking {
      val idFile = dataRoot / "client-id"
      if os.exists(idFile) then
        val id = os.read(idFile).trim
        if id.nonEmpty then id
        else
          val newId = UUID.randomUUID().toString
          os.write.over(idFile, newId, createFolders = true)
          newId
      else
        val newId = UUID.randomUUID().toString
        os.write.over(idFile, newId, createFolders = true)
        newId
    }

  /**
   * Load persisted queue from previous session.
   * The file contains a batch envelope: { "client_id": "...", "events": [...] }
   * We only care about the events array.
   */
  private def loadPersistedQueue: IO[List[TelemetryEvent]] =
    IO.blocking {
      val qFile = dataRoot / "telemetry-queue.json"
      if os.exists(qFile) then
        try
          val raw = os.read(qFile)
          io.circe.parser.parse(raw).toOption
            .flatMap { json =>
              // Try batch envelope format: { "events": [...] }
              json.hcursor.downField("events").as[List[TelemetryEvent]].toOption
                .orElse(json.asArray.map(_.flatMap(_.as[TelemetryEvent].toOption).toList))
            }
            .getOrElse(Nil)
        catch
          case _: Exception => Nil
      else Nil
    }.flatMap { events =>
      if events.nonEmpty then
        IO.blocking(os.remove(dataRoot / "telemetry-queue.json")).as(events)
      else IO.pure(Nil)
    }

  /** Create a TelemetryReporter. Returns None if telemetry is disabled. */
  def create(
    endpoint: String = DefaultEndpoint,
    sender: TelemetrySender = HttpTelemetrySender
  ): IO[Option[TelemetryReporter]] =
    if !isEnabled then IO.pure(None)
    else
      for
        clientId <- loadOrCreateClientId
        persisted <- loadPersistedQueue
        queue <- Ref[IO].of(persisted)
        version = nebflow.Version.string
        osName = detectOs
        reporter = new TelemetryReporter(clientId, endpoint, version, osName, queue, sender)
        // Flush any persisted events from previous session
        _ <- reporter.flush
      yield Some(reporter)

end TelemetryReporter
