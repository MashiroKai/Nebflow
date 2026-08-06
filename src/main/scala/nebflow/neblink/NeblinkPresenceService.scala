package nebflow.neblink

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.decode
import io.circe.syntax.*
import nebflow.core.NebflowLogger

import java.net.URI
import java.net.http.{HttpClient, WebSocket}
import java.util.concurrent.*
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Manages outgoing WebSocket presence connections to NebLink peers.
 *
 * When device A discovers device B via the NebLink Server, A opens a persistent
 * WebSocket to `ws://B:8080/api/neblink/presence`. As long as the WS is open, both
 * devices consider each other online.
 *
 * Heartbeat: ping every 10s; if no pong for 20s the connection is forcibly closed.
 *
 * Auto-reconnect: when a connection drops unexpectedly (TCP RST, heartbeat
 * timeout, sleep/wake), an exponential-backoff loop immediately starts trying
 * to reconnect: 1s -> 2s -> 4s -> 8s -> 16s -> 30s (capped). This ensures
 * sub-second recovery when the network recovers, instead of waiting up to
 * 5 minutes for the next periodic scan. The loop stops after ~20 attempts
 * (~5 min total) or when the peer is explicitly disconnected (left network /
 * logout).
 */
final class NeblinkPresenceService(
  neblinkService: NeblinkService,
  serverPort: Int
)(dispatcher: Dispatcher[IO]):
  private val logger = NebflowLogger.forName("nebflow.neblink.presence")

  private case class PresenceConnection(
    ws: WebSocket,
    alive: AtomicBoolean,
    lastPong: AtomicLong,
    heartbeat: ScheduledExecutorService
  )

  /** deviceId -> active outgoing connection. */
  private val connections = new ConcurrentHashMap[String, PresenceConnection]()

  /** Peers currently in the reconnection loop (deviceId -> PeerInfo). */
  private val reconnecting = new ConcurrentHashMap[String, PeerInfo]()

  /** Device IDs whose reconnection should stop (explicit disconnect / peer left network). */
  private val cancelReconnect = new ConcurrentHashMap[String, java.lang.Boolean]()

  // ===== Public API =====

  /**
   * Reconcile active WS connections with a freshly scanned peer list.
   *
   * - Upserts discovered peers into the NeblinkService peer list.
   * - Connects to peers that are in the list but have no active WS (and aren't
   *   already being auto-reconnected).
   * - Disconnects from peers that have a WS but are no longer in the list.
   * - Cancels auto-reconnect for peers that left the network.
   */
  def syncPeers(peers: List[PeerInfo]): IO[Unit] =
    val peerIds = peers.iterator.map(_.deviceId).toSet
    val staleIds =
      connections.keySet().asScala.filterNot(peerIds.contains).toList
    val staleReconnectIds =
      reconnecting.keySet().asScala.filterNot(peerIds.contains).toList
    for
      _ <- peers.traverse_(peer => neblinkService.upsertPeer(peer))
      // Disconnect peers that left the network
      _ <- IO.blocking(staleIds.foreach(id => disconnectPeer(id)))
      _ <- staleIds.traverse_(id => neblinkService.removePeer(id))
      // Cancel reconnection for peers no longer in the network
      _ <- IO.blocking(staleReconnectIds.foreach(id => cancelReconnect.put(id, true)))
      // Connect to peers without active connection, skip those already reconnecting
      _ <- peers
        .filter(p => !connections.containsKey(p.deviceId) && !reconnecting.containsKey(p.deviceId))
        .traverse_(p => connect(p).start.void)
    yield ()

  end syncPeers

  /** Establish an outgoing WS presence connection to a peer. No-op if already connected. */
  def connect(peer: PeerInfo): IO[Unit] =
    if connections.containsKey(peer.deviceId) then IO.unit
    else
      extractHost(peer.address) match
        case None => IO.unit
        case Some(host) =>
          neblinkService.identity
            .flatMap { id =>
              IO.blocking {
                val wsUri = buildWsUri(host, id)
                try
                  val alive = new AtomicBoolean(true)
                  val lastPong = new AtomicLong(System.currentTimeMillis())
                  val heartbeat = Executors.newSingleThreadScheduledExecutor { r =>
                    val t = new Thread(r, s"presence-hb-${peer.deviceName}")
                    t.setDaemon(true)
                    t
                  }

                  val listener = new PresenceWsListener(this, peer)
                  val client = HttpClient
                    .newBuilder()
                    .proxy(java.net.ProxySelector.of(null)) // bypass HTTP proxy for P2P
                    .build()
                  val ws = client
                    .newWebSocketBuilder()
                    .buildAsync(URI.create(wsUri), listener)
                    .get(5, TimeUnit.SECONDS)

                  val conn = PresenceConnection(ws, alive, lastPong, heartbeat)
                  connections.put(peer.deviceId, conn)

                  // Heartbeat: send ping every 5s; force-close if pong overdue (> 10s)
                  heartbeat.scheduleAtFixedRate(
                    { () =>
                      try
                        if alive.get() then
                          if System.currentTimeMillis() - lastPong.get() > 10_000L then
                            logger.debugSync(s"Heartbeat timeout: ${peer.deviceName}")
                            // Force immediate cleanup — don't rely on onClose (may never fire
                            // if the TCP connection is broken, e.g. after sleep/wake)
                            val zombie = connections.remove(peer.deviceId)
                            if zombie != null then
                              try zombie.heartbeat.shutdownNow()
                              catch
                                case _: Exception => ()
                            // Remove peer and trigger auto-reconnect
                            dispatcher.unsafeRunAndForget(
                              neblinkService.removePeer(peer.deviceId) *>
                                logger.info(s"Heartbeat timeout: ${peer.deviceName}, auto-reconnecting...") *>
                                startReconnect(peer)
                            )
                            try ws.sendClose(WebSocket.NORMAL_CLOSURE, "heartbeat timeout")
                            catch case _: Exception => ()
                          else ws.sendText("""{"type":"ping"}""", true)
                      catch case _: Exception => ()
                    },
                    5,
                    5,
                    TimeUnit.SECONDS
                  )

                  Right(())
                catch
                  case _: java.util.concurrent.TimeoutException =>
                    Left("timeout")
                  case e: Exception =>
                    Left(e.getMessage)
                end try
              }.flatMap {
                case Right(_) => logger.debug(s"Presence connected: ${peer.deviceName} ($host)")
                case Left(err) => logger.debug(s"Presence connect failed: ${peer.deviceName} - $err")
              }
            }
            .handleErrorWith(e => logger.debug(s"Presence connect error: ${e.getMessage}"))

  /** Explicitly disconnect from a peer by deviceId. Cancels any pending reconnection. */
  def disconnect(deviceId: String): IO[Unit] =
    IO.blocking(disconnectPeer(deviceId))

  /** Disconnect all peers and cancel all reconnections (e.g. on logout). */
  def disconnectAll(): IO[Unit] =
    IO.blocking {
      // Signal all reconnection loops to stop
      val allIds = Set[String]() ++ connections.keySet().asScala ++ reconnecting.keySet().asScala
      allIds.foreach(id => cancelReconnect.put(id, true))
      allIds.foreach(id => reconnecting.remove(id))
      // Close all active connections
      connections.keySet().asScala.foreach(id => disconnectPeer(id))
    }

  // ===== Internal (called from WS listener / heartbeat threads) =====

  /**
   * Called by the WS listener when the connection closes or errors.
   * If the close was unexpected (alive still true), removes the peer and
   * starts auto-reconnection immediately.
   */
  private[neblink] def onClosed(deviceId: String, peer: PeerInfo): Unit =
    val conn = connections.remove(deviceId)
    if conn != null then
      try conn.heartbeat.shutdownNow()
      catch case _: Exception => ()
      if conn.alive.get() then
        // Unexpected close — remove peer and auto-reconnect
        dispatcher.unsafeRunAndForget(
          neblinkService.removePeer(deviceId) *>
            logger.info(s"Presence disconnected: ${peer.deviceName}, auto-reconnecting...") *>
            startReconnect(peer)
        )
    // If conn is null, heartbeat-timeout or disconnectPeer already handled it.

  /** Called by the WS listener when a pong frame arrives — refreshes liveness. */
  private[neblink] def updateLastPong(deviceId: String): Unit =
    val conn = connections.get(deviceId)
    if conn != null then conn.lastPong.set(System.currentTimeMillis())

  /** Called by the WS listener when a data message arrives from a peer. */
  private[neblink] def onDataReceived(payload: Json): Unit =
    dispatcher.unsafeRunAndForget(neblinkService.handleDataMessage(payload))

  /** Send a data message to a connected peer over the WS presence connection. */
  def sendData(deviceId: String, channel: String, payload: Json): IO[Unit] =
    IO.blocking {
      val conn = connections.get(deviceId)
      if conn != null then
        val msg = Json.obj(
          "type" -> "data".asJson,
          "channel" -> channel.asJson,
          "payload" -> payload
        )
        conn.ws.sendText(msg.noSpaces, true)
      ()
    }

  /**
   * Start an auto-reconnection loop for a peer. Idempotent: if the peer is
   * already being reconnected, or was explicitly cancelled, does nothing.
   */
  private def startReconnect(peer: PeerInfo): IO[Unit] =
    IO.blocking {
      if cancelReconnect.remove(peer.deviceId) != null then
        reconnecting.remove(peer.deviceId)
        false // was cancelled
      else reconnecting.putIfAbsent(peer.deviceId, peer) == null // true if we won the slot
    }.flatMap {
      case false => IO.unit
      case true => reconnectLoop(peer, 0)
    }

  /**
   * Reconnection with immediate first attempt, then exponential backoff:
   * 0s (immediate) -> 1s -> 2s -> 4s -> 8s -> 16s -> 30s (capped).
   * Gives up after 20 attempts (~5 min total), falling back to periodic scan.
   */
  private def reconnectLoop(peer: PeerInfo, attempt: Int): IO[Unit] =
    if cancelReconnect.remove(peer.deviceId) != null then
      reconnecting.remove(peer.deviceId)
      IO.unit
    else if attempt >= 20 then
      reconnecting.remove(peer.deviceId)
      logger.info(s"Reconnect gave up after $attempt attempts: ${peer.deviceName}")
    else if connections.containsKey(peer.deviceId) then
      // Already reconnected (e.g. by syncPeers)
      reconnecting.remove(peer.deviceId)
      IO.unit
    else
      // attempt 0: immediate. Then 1, 2, 4, 8, 16, 30, 30, ...
      val delaySecs = attempt match
        case 0 => 0
        case n => math.min(30, 1 << (n - 1))
      IO.sleep(delaySecs.seconds) *>
        IO.blocking(Option(cancelReconnect.remove(peer.deviceId))).flatMap {
          case Some(_) =>
            // Cancelled during sleep
            reconnecting.remove(peer.deviceId)
            IO.unit
          case None =>
            connect(peer)
              .flatMap { _ =>
                if connections.containsKey(peer.deviceId) then
                  // Success — re-add peer to NeblinkService and clean up
                  reconnecting.remove(peer.deviceId)
                  neblinkService.upsertPeer(peer) *>
                    logger.info(s"Reconnected to ${peer.deviceName} after ${attempt + 1} attempt(s)")
                else reconnectLoop(peer, attempt + 1)
              }
              .handleErrorWith(_ => reconnectLoop(peer, attempt + 1))
        }

  // ===== Explicit disconnect (cancels reconnection) =====

  /** Close WS, shut down heartbeat, remove from map. Signals reconnection to stop. */
  private def disconnectPeer(deviceId: String): Unit =
    cancelReconnect.put(deviceId, true)
    reconnecting.remove(deviceId)
    val conn = connections.remove(deviceId)
    if conn != null then
      conn.alive.set(false)
      try conn.heartbeat.shutdownNow()
      catch case _: Exception => ()
      try conn.ws.sendClose(WebSocket.NORMAL_CLOSURE, "disconnect")
      catch case _: Exception => ()

  // ===== Helpers =====

  /** Extract host from "http://100.x.y.z:8080" -> "100.x.y.z". */
  private def extractHost(address: String): Option[String] =
    try
      val stripped = address.replaceFirst("https?://", "")
      val colonIdx = stripped.indexOf(':')
      val host = if colonIdx > 0 then stripped.substring(0, colonIdx) else stripped
      Option(host.trim).filter(_.nonEmpty)
    catch case _: Exception => None

  /** Build the WS URI with our device info as query params. */
  private def buildWsUri(host: String, id: DeviceIdentity): String =
    val params = Map(
      "deviceId" -> id.deviceId,
      "deviceName" -> id.deviceName,
      "platform" -> id.platform,
      "capabilities" -> id.capabilities.asJson.noSpaces,
      "port" -> serverPort.toString
    )
    val query = params.map((k, v) => s"$k=${enc(v)}").mkString("&")
    s"ws://$host:$serverPort/api/neblink/presence?$query"

  private def enc(s: String): String =
    try java.net.URLEncoder.encode(s, "UTF-8")
    catch case _: Exception => s
end NeblinkPresenceService

/**
 * JDK WebSocket.Listener for outgoing presence connections.
 * Forwards events back to NeblinkPresenceService — stateless on its own.
 * Uses *Sync logging because callbacks run on JDK WS threads (no IO context).
 */
private final class PresenceWsListener(
  service: NeblinkPresenceService,
  peer: PeerInfo
) extends WebSocket.Listener:
  private val logger = NebflowLogger.forName("nebflow.neblink.presence")

  override def onOpen(ws: WebSocket): Unit =
    ws.request(1)

  override def onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage[?] =
    try
      val text = data.toString
      decode[Json](text) match
        case Right(json) =>
          json.hcursor.downField("type").as[String].getOrElse("") match
            case "pong" => service.updateLastPong(peer.deviceId)
            case "ping" =>
              // Respond to server-side heartbeat
              try ws.sendText("""{"type":"pong"}""", true)
              catch case _: Exception => ()
            case "data" =>
              // Forward data message to registered handlers
              val payload = json.hcursor.downField("payload").focus.getOrElse(Json.Null)
              service.onDataReceived(payload)
            case _ => ()
        case Left(_) => ()
    catch case _: Exception => ()
    end try
    ws.request(1)
    null

  end onText

  override def onClose(ws: WebSocket, statusCode: Int, reason: String): CompletionStage[?] =
    service.onClosed(peer.deviceId, peer)
    null

  override def onError(ws: WebSocket, error: Throwable): Unit =
    logger.debugSync(s"Presence WS error: ${peer.deviceName} - ${error.getMessage}")
    service.onClosed(peer.deviceId, peer)
end PresenceWsListener
