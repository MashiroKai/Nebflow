package nebflow.mesh

import cats.effect.IO
import cats.syntax.all.*
import io.circe.{Decoder, Json, JsonObject}
import io.circe.syntax.*
import nebflow.core.NebflowLogger
import nebflow.core.tools.{ToolContext, ToolRegistry}

import scala.concurrent.duration.*

/**
 * Cloud relay service for cross-device tool execution.
 *
 * When two devices are on different networks (no P2P), the relay server
 * acts as an intermediary: device A submits a command, device B polls and
 * executes it, then device A fetches the result.
 *
 * All methods are no-ops when not logged in.
 *
 * Latency: when a MeshRelayClient is wired, command delivery and result return
 * are real-time (WebSocket push + a Deferred signal). HTTP polling
 * (`fetchResultBlocking` / the background loop) remains as a fallback for when
 * the WS channel is down.
 */
class RelayService private (meshService: MeshService):

  private val logger = NebflowLogger.forName("nebflow.relay")

  /** WS relay client, wired in at startup. May be None (pure polling mode). */
  private var wsClient: Option[MeshRelayClient] = None

  /** Wire the WebSocket relay client. Enables real-time command/result delivery. */
  def setWsClient(c: MeshRelayClient): Unit = wsClient = Some(c)

  // ===== Submit (caller side) =====

  /**
   * Submit a command to a remote device via cloud relay, then wait for the result.
   * Returns the tool output or an error message.
   *
   * Delivery path: when the WS client is connected, the result is delivered via a
   * Deferred completed by an incoming `relay-result` push (sub-second). Otherwise it
   * falls back to HTTP polling (relay/fetch-result every 2s).
   */
  def submitAndWait(toDeviceId: String, action: String, params: JsonObject): IO[Either[String, String]] =
    for
      id <- meshService.identity
      relayId <- meshService.callCloudFunction(
        "relay/submit",
        "fromDeviceId" -> id.deviceId.asJson,
        "toDeviceId" -> toDeviceId.asJson,
        "relayAction" -> action.asJson,
        "params" -> params.asJson
      ).map(_.hcursor.downField("relayId").as[String].getOrElse(""))
        .handleErrorWith(_ => IO.pure(""))
      result <-
        if relayId.isEmpty then
          IO.pure(Left("Relay submit failed. Device may be offline or relay unreachable."))
        else awaitResult(relayId)
    yield result

  /** Wait for a relay command's result: WS Deferred first, HTTP polling fallback. */
  private def awaitResult(relayId: String): IO[Either[String, String]] =
    wsClient match
      case Some(client) =>
        client.awaitResult(relayId).flatMap {
          case Some(deferred) =>
            // Real-time path: wait for the WS push, with a generous timeout, then clean up.
            // On timeout (WS dropped mid-flight), fall back to HTTP polling.
            deferred.get.timeoutTo(60.seconds, fallbackPoll(relayId))
              .guarantee(IO(client.removeWaiter(relayId)))
          case None => fallbackPoll(relayId)
        }
      case None => fallbackPoll(relayId)

  /** HTTP polling fallback (and the pre-WS code path). Polls relay/fetch-result every 2s. */
  private def fallbackPoll(relayId: String): IO[Either[String, String]] =
    fetchResultBlocking(relayId, timeout = 180.seconds)

  /**
   * Poll for relay command result. Blocks until result is available or timeout.
   * Polls every 2 seconds, up to 180 seconds.
   */
  def fetchResultBlocking(relayId: String, timeout: Duration = 180.seconds): IO[Either[String, String]] =
    val pollInterval = 2.seconds

    def pollOnce: IO[Option[Either[String, String]]] =
      meshService.callCloudFunction("relay/fetch-result", "relayId" -> relayId.asJson).flatMap { json =>
        val status = json.hcursor.downField("status").as[String].getOrElse("pending")
        status match
          case "done" =>
            IO.pure(Some(Right(json.hcursor.downField("result").as[String].getOrElse(""))))
          case "error" =>
            IO.pure(Some(Left(json.hcursor.downField("error").as[String].getOrElse("Unknown error"))))
          case _ => IO.pure(None)
      }

    def loop(remaining: Duration): IO[Either[String, String]] =
      if remaining <= Duration.Zero then IO.pure(Left("Relay timeout — remote device may be offline"))
      else
        pollOnce.flatMap {
          case Some(result) => IO.pure(result)
          case None => IO.sleep(pollInterval).flatMap(_ => loop(remaining - pollInterval))
        }

    loop(timeout)
  end fetchResultBlocking

  // ===== Execute (target side) =====

  /** Target device polls for pending relay commands. */
  def poll: IO[List[RelayCommand]] =
    for
      id <- meshService.identity
      resp <- meshService.callCloudFunction("relay/poll", "deviceId" -> id.deviceId.asJson)
      commands <- IO.fromEither(
        resp.hcursor
          .downField("commands")
          .as[List[RelayCommand]]
          .leftMap(e => new RuntimeException(s"Decode commands: ${e.getMessage}"))
      )
    yield commands

  /** Target device submits execution result. */
  def submitResult(relayId: String, result: String, error: Option[String]): IO[Unit] =
    meshService
      .callCloudFunction(
        "relay/result",
        "relayId" -> relayId.asJson,
        "result" -> result.asJson,
        "error" -> error.getOrElse("").asJson
      )
      .void

  /**
   * Poll for pending relay commands from other devices and execute them locally.
   * This makes the local device act as a relay target. Used as the HTTP-polling fallback.
   */
  def processCommands: IO[Unit] =
    for
      commands <- poll.handleErrorWith(e => logger.debug(s"Relay poll: ${e.getMessage}").as(Nil))
      _ <- commands.traverse_ { cmd =>
        executeCommand(cmd).handleErrorWith(e =>
          logger.warn(s"Relay exec ${cmd.action} (${cmd.relayId.take(8)}) failed: ${e.getMessage}")
        )
      }
    yield ()

  private def executeCommand(cmd: RelayCommand): IO[Unit] =
    val toolOpt = ToolRegistry.TOOL_MAP.get(cmd.action)
    toolOpt match
      case Some(tool) =>
        val params = cmd.params.asObject.getOrElse(JsonObject.empty)
        val ctx = ToolContext(projectRoot = System.getProperty("user.dir", "."))
        for
          result <- tool.call(params, ctx)
          _ <- result match
            case Right(output) => submitResult(cmd.relayId, output, None)
            case Left(err)     => submitResult(cmd.relayId, "", Some(err.message))
        yield ()
      case None =>
        submitResult(cmd.relayId, "", Some(s"Unknown tool: ${cmd.action}"))

  /**
   * Execute a relay command pushed via WebSocket (server `relay` push).
   * Parses the raw JSON frame and runs the tool, then submits the result back.
   * Exposed for MeshRelayClient; mirrors executeCommand but operates on the push payload.
   */
  private[mesh] def executeRelayCommand(json: io.circe.Json): IO[Unit] =
    val hc = json.hcursor
    for
      relayId <- IO.fromEither(
        hc.downField("relayId").as[String].leftMap(_ => new RuntimeException("missing relayId"))
      )
      action <- IO.fromEither(
        hc.downField("action").as[String].leftMap(_ => new RuntimeException("missing action"))
      )
      params = hc.downField("params").as[JsonObject].getOrElse(JsonObject.empty)
      toolOpt = ToolRegistry.TOOL_MAP.get(action)
      _ <- toolOpt match
        case Some(tool) =>
          val ctx = ToolContext(projectRoot = System.getProperty("user.dir", "."))
          tool.call(params, ctx).flatMap {
            case Right(output) => submitResult(relayId, output, None)
            case Left(err)     => submitResult(relayId, "", Some(err.message))
          }
        case None => submitResult(relayId, "", Some(s"Unknown tool: $action"))
    yield ()

  // ===== Background loop (fallback) =====

  /**
   * Start a background relay poller.
   *
   * When the WS client is connected, commands arrive via push, so this loop only
   * sweeps occasionally (60s) to catch anything missed. When WS is down, it polls
   * every 10s as before. Checks login status + WS status each iteration.
   */
  def startBackgroundPoller(dispatcher: cats.effect.std.Dispatcher[IO]): Unit =
    dispatcher.unsafeRunAndForget(loop)

  private def loop: IO[Unit] =
    meshService.isLoggedIn.flatMap { loggedIn =>
      if !loggedIn then IO.sleep(10.seconds).flatMap(_ => loop)
      else
        // Pick interval based on whether WS is delivering commands in real time.
        wsClient match
          case Some(client) =>
            client.isConnected.flatMap { up =>
              val interval = if up then 60.seconds else 10.seconds
              val sweep = if up then IO.unit else processCommands.handleErrorWith(_ => IO.unit)
              sweep *> IO.sleep(interval).flatMap(_ => loop)
            }
          case None =>
            processCommands.handleErrorWith(_ => IO.unit) *>
              IO.sleep(10.seconds).flatMap(_ => loop)
    }

end RelayService

/** A pending relay command received by the target device. */
case class RelayCommand(relayId: String, fromDeviceId: String, action: String, params: Json)

object RelayCommand:
  given Decoder[RelayCommand] = Decoder.instance { c =>
    for
      relayId     <- c.downField("relayId").as[String]
      fromDeviceId <- c.downField("fromDeviceId").as[String]
      action      <- c.downField("action").as[String]
      params      <- c.downField("params").as[Json]
    yield RelayCommand(relayId, fromDeviceId, action, params)
  }

object RelayService:
  /** Create a RelayService. */
  def apply(meshService: MeshService): RelayService =
    new RelayService(meshService)
