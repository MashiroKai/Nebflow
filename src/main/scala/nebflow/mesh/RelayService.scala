package nebflow.mesh

import cats.effect.IO
import cats.syntax.all.*
import io.circe.{Decoder, Json, JsonObject}
import io.circe.syntax.*
import nebflow.core.NebflowLogger
import nebflow.core.tools.{ToolContext, ToolRegistry}
import nebflow.shared.*

import scala.concurrent.duration.*

/**
 * Cloud relay service for cross-device tool execution.
 *
 * When two devices are on different networks (no P2P), the relay server
 * acts as an intermediary: device A submits a command, device B polls and
 * executes it, then device A fetches the result.
 *
 * All methods are no-ops when not logged in.
 */
class RelayService private (meshService: MeshService):

  private val logger = NebflowLogger.forName("nebflow.relay")

  // ===== Submit (caller side) =====

  /**
   * Submit a command to a remote device via cloud relay.
   * Returns relayId for later result polling.
   */
  def submit(toDeviceId: String, action: String, params: Json): IO[String] =
    for
      id <- meshService.identity
      resp <- meshService.callCloudFunction(
        "relay/submit",
        "fromDeviceId" -> id.deviceId.asJson,
        "toDeviceId" -> toDeviceId.asJson,
        "relayAction" -> action.asJson,
        "params" -> params
      )
      relayId <- IO.fromEither(
        resp.hcursor
          .downField("relayId")
          .as[String]
          .leftMap(e => new RuntimeException(s"Missing relayId: ${e.getMessage}"))
      )
    yield relayId

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
   * This makes the local device act as a relay target.
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

  // ===== Background loop =====

  /**
   * Start a background relay poller (10 second interval).
   * Called once on startup — checks login status each iteration.
   */
  def startBackgroundPoller(dispatcher: cats.effect.std.Dispatcher[IO]): Unit =
    dispatcher.unsafeRunAndForget(loop)

  private def loop: IO[Unit] =
    meshService.isLoggedIn.flatMap { loggedIn =>
      val action = if loggedIn then processCommands.handleErrorWith(_ => IO.unit) else IO.unit
      action.flatMap(_ => IO.sleep(10.seconds)).flatMap(_ => loop)
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
