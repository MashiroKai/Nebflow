package nebflow.neblink

import cats.effect.{Deferred, IO}
import cats.effect.std.Dispatcher
import cats.syntax.all.*
import io.circe.parser.decode
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.{NebflowLogger, PathUtil}
import nebflow.core.tools.{ToolContext, ToolRegistry}

import java.net.URI
import java.net.http.{HttpClient, WebSocket}
import java.util.concurrent.*
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}

import scala.concurrent.duration.*

/**
 * WebSocket relay tunnel to the NebLink Server.
 *
 * Device B connects this tunnel to the server so that cross-network relay-exec
 * requests (from device A) can be routed to B via the server. Without this
 * tunnel, devices on different networks cannot reach each other's P2P endpoints.
 *
 * Protocol (server <-> device B):
 *   S->B: {"type":"relay_request","requestId":"uuid","action":"Bash","params":{...},"projectRoot":"..."}
 *   B->S: {"type":"relay_response","requestId":"uuid","output":"...","error":""}
 *   Heartbeat: {"type":"ping"} / {"type":"pong"}
 *
 * Auto-reconnects with exponential backoff (0s -> 1s -> 2s ... -> 30s cap).
 * Token is read live from NeblinkClient so re-login after token expiry works.
 * Always-on by design — started in GatewayMain when NebLink Server is configured.
 */
final class NeblinkRelayTunnel(
  neblinkService: NeblinkService,
  serverUrl: String,
  tokenGetter: () => Option[String]
)(dispatcher: Dispatcher[IO]):
  private val logger = NebflowLogger.forName("nebflow.neblink.relay")

  @volatile private var wsRef: Option[WebSocket] = None
  private val running = new AtomicBoolean(true)
  private val alive = new AtomicBoolean(false)
  private val lastPong = new AtomicLong(System.currentTimeMillis())
  private var heartbeat: Option[ScheduledExecutorService] = None

  /** Check if the relay tunnel is currently connected (for status reporting). */
  def isAlive: Boolean = alive.get()

  /** Start the relay tunnel connection loop. Runs in background until stop(). */
  def connect(): IO[Unit] =
    logger.info("Starting relay tunnel to NebLink Server") *>
      connectLoop(0)

  /** Gracefully stop the tunnel. */
  def stop(): IO[Unit] =
    IO.blocking {
      running.set(false)
      alive.set(false)
      heartbeat.foreach { hb => try hb.shutdownNow() catch case _: Exception => () }
      wsRef.foreach { w => try w.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown") catch case _: Exception => () }
    }

  // ===== Connection loop =====

  private def connectLoop(attempt: Int): IO[Unit] =
    if !running.get() then IO.unit
    else
      val delay = if attempt == 0 then 0.seconds else math.min(30, 1 << (attempt - 1)).seconds
      IO.sleep(delay).flatMap { _ =>
        if !running.get() then IO.unit
        else
          tokenGetter() match
            case None =>
              val wait = math.min(30, 1 << math.min(attempt, 4)).seconds
              logger.debug(s"Relay tunnel: no session token yet, retrying in ${wait.toSeconds}s...").flatMap { _ =>
                IO.sleep(wait).flatMap { _ => connectLoop(attempt + 1) }
              }
            case Some(token) =>
              neblinkService.identity
                .flatMap(id => connectOnce(id, token))
                .flatMap { _ =>
                  if running.get() then
                    logger.info("Relay tunnel disconnected, reconnecting...").flatMap { _ =>
                      connectLoop(0) // reset for immediate retry
                    }
                  else IO.unit
                }
                .handleErrorWith { e =>
                  logger.warn(s"Relay tunnel error: ${e.getMessage}").flatMap { _ =>
                    if running.get() then connectLoop(attempt + 1) else IO.unit
                  }
                }
      }

  /** Establish a single WS connection; returns when the connection ends. */
  private def connectOnce(id: DeviceIdentity, token: String): IO[Unit] =
    Deferred[IO, Unit].flatMap { closed =>
      IO.blocking {
        val wsUri = buildRelayWsUri(serverUrl, id)
        val listener = new RelayWsListener(this, closed, dispatcher)
        val client = HttpClient
          .newBuilder()
          .version(HttpClient.Version.HTTP_1_1) // avoid HTTP/2 TLS issues with Caddy
          .proxy(java.net.ProxySelector.of(null)) // bypass system proxy
          .connectTimeout(java.time.Duration.ofSeconds(15))
          .build()
        val w = client
          .newWebSocketBuilder()
          .header("Authorization", s"Bearer $token")
          .buildAsync(URI.create(wsUri), listener)
          .get(10, TimeUnit.SECONDS)
        wsRef = Some(w)
        alive.set(true)
        lastPong.set(System.currentTimeMillis())
        startHeartbeat(w)
        logger.infoSync(s"Relay tunnel connected: $wsUri")
        ()
      } *> closed.get // block until WS closes
        <* IO.blocking {
          alive.set(false)
          stopHeartbeat()
          wsRef = None
        }
    }

  // ===== Relay request handling (called from WS listener thread) =====

  /** Execute a relay_request locally and send the response back over WS. */
  private[neblink] def handleRelayRequest(msg: Json, ws: WebSocket): IO[Unit] =
    val hc = msg.hcursor
    val requestId = hc.downField("requestId").as[String].getOrElse("")
    val action = hc.downField("action").as[String].getOrElse("")
    // Expand ~ to *this* device's user.home — must happen on the receiver so
    // the path resolves to the local filesystem (e.g. C:\Users\kai on Windows),
    // not the sender's home directory.
    val params = PathUtil.expandPathParams(
      hc.downField("params").as[JsonObject].getOrElse(JsonObject.empty)
    )
    val projectRoot =
      hc.downField("projectRoot").as[String].getOrElse(System.getProperty("user.dir", "."))

    val toolOpt = ToolRegistry.TOOL_MAP.get(action)
    for
      (output, error) <- action match
        case "FileTransfer" =>
          FileTransferAction.handle(params).map {
            case Right(json) => (json.noSpaces, "")
            case Left(err)   => ("", err)
          }
        case "Notify" =>
          val payload = params("payload").getOrElse(Json.Null)
          neblinkService.handleDataMessage(payload).as(("notified", ""))
        case "RemoteUpdate" =>
          val beta = params("beta").flatMap(_.asBoolean).getOrElse(false)
          RemoteUpdateAction.runInstallScript(beta).flatMap {
            case Right(msg) =>
              // Schedule restart — same logic as RestApiRoutes POST /neblink/update
              IO.blocking(nebflow.core.RestartHelper.spawnRestart()) *>
                IO.delay(dispatcher.unsafeRunAndForget(IO.sleep(1.second) *> IO(System.exit(0)))) *>
                IO.pure((msg, ""))
            case Left(err) => IO.pure(("", err))
          }
        case _ =>
          toolOpt match
            case Some(tool) =>
              val ctx = ToolContext(projectRoot = projectRoot, isRemoteExec = true)
              tool.call(params, ctx).attempt.map {
                case Right(Right(result)) => (result, "")
                case Right(Left(err))     => ("", err.message)
                case Left(e)              => ("", s"Tool execution failed: ${e.getMessage}")
              }
            case None =>
              IO.pure(("", s"Unknown tool: $action"))
      resp = Json.obj(
        "type" -> "relay_response".asJson,
        "requestId" -> requestId.asJson,
        "output" -> output.asJson,
        "error" -> error.asJson
      )
      _ <- IO.blocking {
        try ws.sendText(resp.noSpaces, true)
        catch case _: Exception => ()
        ()
      }
    yield ()

  /** Called by WS listener when pong arrives — refreshes heartbeat liveness. */
  private[neblink] def updateLastPong(): Unit =
    lastPong.set(System.currentTimeMillis())

  // ===== Heartbeat =====

  private def startHeartbeat(ws: WebSocket): Unit =
    val hb = Executors.newSingleThreadScheduledExecutor { r =>
      val t = new Thread(r, "relay-tunnel-hb")
      t.setDaemon(true)
      t
    }
    heartbeat = Some(hb)
    hb.scheduleAtFixedRate(
      { () =>
        try
          if alive.get() then
            if System.currentTimeMillis() - lastPong.get() > 30_000L then
              logger.debugSync("Relay tunnel heartbeat timeout, closing for reconnect")
              try ws.sendClose(WebSocket.NORMAL_CLOSURE, "heartbeat timeout")
              catch case _: Exception => ()
            else ws.sendText("""{"type":"ping"}""", true)
        catch case _: Exception => ()
      },
      10,
      10,
      TimeUnit.SECONDS
    )

  private def stopHeartbeat(): Unit =
    heartbeat.foreach { hb => try hb.shutdownNow() catch case _: Exception => () }
    heartbeat = None

  // ===== Helpers =====

  /** Convert serverUrl (https://...) to ws/wss and build the relay-ws endpoint. */
  private def buildRelayWsUri(serverUrl: String, id: DeviceIdentity): String =
    val wsBase = serverUrl
      .replaceFirst("https://", "wss://")
      .replaceFirst("http://", "ws://")
    val encodedId = try java.net.URLEncoder.encode(id.deviceId, "UTF-8")
    catch case _: Exception => id.deviceId
    s"$wsBase/api/device/relay-ws?deviceId=$encodedId"

end NeblinkRelayTunnel

/**
 * JDK WebSocket.Listener for the relay tunnel.
 * Forwards events to NeblinkRelayTunnel — stateless on its own.
 */
private final class RelayWsListener(
  tunnel: NeblinkRelayTunnel,
  closed: cats.effect.Deferred[IO, Unit],
  dispatcher: Dispatcher[IO]
) extends WebSocket.Listener:
  private val logger = NebflowLogger.forName("nebflow.neblink.relay")

  override def onOpen(ws: WebSocket): Unit =
    ws.request(1)

  override def onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage[?] =
    try
      decode[Json](data.toString) match
        case Right(json) =>
          json.hcursor.downField("type").as[String].getOrElse("") match
            case "relay_request" =>
              dispatcher.unsafeRunAndForget(tunnel.handleRelayRequest(json, ws))
            case "ping" =>
              try ws.sendText("""{"type":"pong"}""", true)
              catch case _: Exception => ()
            case "pong" =>
              tunnel.updateLastPong()
            case _ => ()
        case Left(_) => ()
    catch case _: Exception => ()
    end try
    ws.request(1)
    null

  override def onClose(ws: WebSocket, statusCode: Int, reason: String): CompletionStage[?] =
    dispatcher.unsafeRunAndForget(closed.complete(()).void)
    null

  override def onError(ws: WebSocket, error: Throwable): Unit =
    logger.debugSync(s"Relay WS error: ${error.getMessage}")
    dispatcher.unsafeRunAndForget(closed.complete(()).void)
end RelayWsListener
