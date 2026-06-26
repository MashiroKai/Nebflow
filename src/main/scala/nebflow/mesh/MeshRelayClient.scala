package nebflow.mesh

import cats.effect.std.Dispatcher
import cats.effect.{Deferred, IO}
import io.circe.parser.decode
import io.circe.Json
import nebflow.core.NebflowLogger

import java.net.URI
import java.net.http.{HttpClient, WebSocket}
import java.util.concurrent.{CompletionStage, ConcurrentHashMap}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}
import scala.concurrent.duration.*

/**
 * Persistent WebSocket client to the relay server's `/ws` endpoint.
 *
 * Replaces the 10s HTTP polling loop with real-time push:
 *   - Server pushes `relay` commands → this device executes them immediately.
 *   - Server pushes `relay-result` → completes a waiting Deferred (caller side, sub-second).
 *   - Server pushes `peer-joined` → triggers immediate discovery on this device.
 *
 * Built on the JDK's built-in WebSocket (zero extra dependencies). The callback-based
 * API is bridged to cats-effect via the shared `Dispatcher` and a `ConcurrentHashMap` of
 * result waiters. All failures degrade gracefully: callers fall back to HTTP polling,
 * and this client reconnects with exponential backoff.
 *
 * Lifecycle: connect() on login (or restored login at startup), disconnect() on logout.
 */
final class MeshRelayClient(
  meshService: MeshService,
  relayService: RelayService,
  dispatcher: Dispatcher[IO]
):
  private val logger = NebflowLogger.forName("nebflow.mesh.ws-client")

  /** Pending result waiters keyed by relayId. Completed by `relay-result` pushes. */
  private val waiters = new ConcurrentHashMap[String, Deferred[IO, Either[String, String]]]()

  /** The live JDK WebSocket, or null when disconnected. */
  private val wsRef = new AtomicReference[WebSocket](null)

  /** Whether a (re)connect attempt is in-flight, to avoid duplicate reconnects. */
  private val connecting = new AtomicBoolean(false)

  /** Set to false by disconnect() to stop the reconnect loop permanently (until next connect). */
  private val alive = new AtomicBoolean(false)

  /** Tracks consecutive reconnect failures for exponential backoff; reset on success. */
  private val reconnectAttempt = new AtomicInteger(0)

  /** Exposed to callers so they can fall back to polling when the WS channel is down. */
  def isConnected: IO[Boolean] = IO(wsRef.get() != null)

  /**
   * Register a Deferred to be completed when the relay result for `relayId` arrives via WS.
   * Returns Some(deferred) only when the WS channel is up; None when down (caller polls).
   */
  def awaitResult(relayId: String): IO[Option[Deferred[IO, Either[String, String]]]] =
    isConnected.flatMap {
      case false => IO.pure(None)
      case true =>
        Deferred[IO, Either[String, String]].map { d =>
          waiters.put(relayId, d)
          Some(d)
        }
    }

  /** Remove a waiter (cleanup on timeout/exit). */
  def removeWaiter(relayId: String): Unit = waiters.remove(relayId)

  /** Connect (or reconnect) to the relay server's /ws. Idempotent; safe to call repeatedly. */
  def connect(): IO[Unit] =
    isConnected.flatMap {
      case true  => IO.unit
      case false => doConnect()
    }

  private def doConnect(): IO[Unit] = IO.blocking {
    if !connecting.compareAndSet(false, true) then ()
    else
      alive.set(true)
      try
        import cats.effect.unsafe.implicits.global
        val account = meshService.account.unsafeRunSync()
        val identity = meshService.identity.unsafeRunSync()
        val cloudUrl = meshService.cloudUrl.unsafeRunSync()
        account match
          case None =>
            connecting.set(false)
          case Some(acc) if cloudUrl.isEmpty =>
            connecting.set(false)
          case Some(acc) =>
            val wsUri = buildWsUri(cloudUrl, acc.userId, identity.deviceId, acc.sessionToken)
            try
              val client = HttpClient.newHttpClient()
              val listener = new RelayWsListener(this)
              val socket = client.newWebSocketBuilder()
                .buildAsync(URI.create(wsUri), listener)
                .get() // throws on failure → caught below
              wsRef.set(socket)
              reconnectAttempt.set(0)
              logger.info(s"WS relay client connected: user=${acc.userId.take(8)} device=${identity.deviceName}")
            catch
              case e: Exception =>
                logger.debug(s"WS connect failed: ${e.getMessage} — will retry")
                scheduleReconnect()
            finally connecting.set(false)
      catch
        case e: Exception =>
          connecting.set(false)
          logger.debug(s"doConnect error: ${e.getMessage}")
  }

  /** Disconnect and stop the reconnect loop. Called on logout. */
  def disconnect(): IO[Unit] = IO.blocking {
    alive.set(false)
    val ws = wsRef.getAndSet(null)
    if ws != null then
      try ws.sendClose(WebSocket.NORMAL_CLOSURE, "logout") catch case _: Exception => ()
    // Fail all pending waiters immediately so callers fall back to HTTP polling
    // instead of blocking until their 60s Deferred timeout elapses.
    val snapshot = new java.util.ArrayList[String]()
    waiters.keySet().forEach(snapshot.add)
    snapshot.forEach { relayId =>
      val w = waiters.remove(relayId)
      if w != null then
        dispatcher.unsafeRunAndForget(w.complete(Left("WS disconnected")))
    }
    ()
  }

  /** Called by the listener on close/error — trigger a reconnect (with backoff) if still alive. */
  private[mesh] def onClosed(): Unit =
    wsRef.set(null)
    if alive.get() then scheduleReconnect()

  /** Schedule a reconnect with exponential backoff capped at 30s. */
  private def scheduleReconnect(): Unit =
    if !alive.get() then ()
    else
      val n = reconnectAttempt.getAndIncrement()
      val secs = if n <= 0 then 2 else math.min(30, math.pow(2.0, n.toDouble).toInt)
      dispatcher.unsafeRunAndForget(IO.sleep(secs.seconds) *> connect())

  private def buildWsUri(cloudUrl: String, userId: String, deviceId: String, token: String): String =
    val wsBase = cloudUrl.stripSuffix("/").replaceFirst("^http", "ws")
    s"$wsBase/ws?userId=${enc(userId)}&deviceId=${enc(deviceId)}&token=${enc(token)}"

  private def enc(s: String): String =
    try java.net.URLEncoder.encode(s, "UTF-8") catch case _: Exception => s

  // ===== Message dispatch (invoked from the WS listener thread) =====

  /** Dispatch a text frame received from the server. All side effects are funneled here. */
  private[mesh] def dispatch(text: String): Unit =
    decode[Json](text) match
      case Right(json) =>
        json.hcursor.downField("type").as[String].getOrElse("") match
          case "relay"        => handleRelayCommand(json)
          case "relay-result" => handleRelayResult(json)
          case "peer-joined"  => handlePeerJoined()
          case _              => () // unknown/legacy message — ignore
      case Left(_) => () // malformed — ignore

  /** Server pushed a command for this device to execute. */
  private def handleRelayCommand(json: Json): Unit =
    dispatcher.unsafeRunAndForget(
      relayService.executeRelayCommand(json).handleErrorWith(e =>
        logger.warn(s"WS relay command failed: ${e.getMessage}")
      )
    )

  /** Server pushed a relay result back to the originator — complete the waiting Deferred. */
  private def handleRelayResult(json: Json): Unit =
    val hc = json.hcursor
    val relayId = hc.downField("relayId").as[String].getOrElse("")
    if relayId.nonEmpty then
      val waiter = waiters.remove(relayId)
      if waiter != null then
        val result = hc.downField("result").as[String].getOrElse("")
        val error = hc.downField("error").as[String].getOrElse("")
        val value: Either[String, String] = if error.nonEmpty then Left(error) else Right(result)
        dispatcher.unsafeRunAndForget(waiter.complete(value))

  /** A new peer appeared on this account — trigger immediate discovery. */
  private def handlePeerJoined(): Unit =
    meshService.syncActor ! SyncCommand.PeerDiscovered
end MeshRelayClient

/**
 * JDK WebSocket.Listener — a thin bridge that forwards frames to MeshRelayClient.
 * All IO side effects live in the client (which holds the Dispatcher), keeping this stateless.
 */
private final class RelayWsListener(client: MeshRelayClient) extends WebSocket.Listener:
  private val logger = NebflowLogger.forName("nebflow.mesh.ws-client")

  override def onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage[?] =
    try client.dispatch(data.toString)
    catch case e: Exception => logger.debug(s"WS onText dispatch error: ${e.getMessage}")
    webSocket.request(1)
    null

  override def onOpen(webSocket: WebSocket): Unit =
    webSocket.request(1)

  override def onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage[?] =
    logger.debug(s"WS closed: $statusCode $reason")
    client.onClosed()
    null

  override def onError(webSocket: WebSocket, error: Throwable): Unit =
    logger.debug(s"WS error: ${error.getMessage}")
    client.onClosed()
end RelayWsListener
