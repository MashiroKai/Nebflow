package nebflow.mesh

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
 * Manages outgoing WebSocket presence connections to Tailscale peers.
 *
 * When device A discovers device B via `tailscale status`, A opens a persistent
 * WebSocket to `ws://B:8080/api/mesh/presence`. As long as the WS is open, both
 * devices consider each other online. When the WS drops (TCP RST or heartbeat
 * timeout), both sides remove the peer.
 *
 * Heartbeat: ping every 10s; if no pong for 20s the connection is forcibly closed.
 * Reconnection: the periodic `syncPeers` call (driven by the sync actor) re-establishes
 * connections for peers still present in the tailnet scan.
 *
 * Built on the JDK's built-in WebSocket (zero extra dependencies), mirroring the
 * pattern used by MeshRelayClient. ProxySelector.of(null) bypasses any configured
 * HTTP proxy so Tailscale traffic goes direct.
 */
final class MeshPresenceService(
  meshService: MeshService,
  serverPort: Int
)(dispatcher: Dispatcher[IO]):
  private val logger = NebflowLogger.forName("nebflow.mesh.presence")

  /**
   * One outgoing presence connection.
   *
   * @param ws        The JDK WebSocket handle.
   * @param alive     False once disconnect() has been called — distinguishes explicit
   *                  disconnect from an unexpected drop.
   * @param lastPong  Epoch millis of the last pong received from the peer.
   * @param heartbeat Scheduled executor for ping/pong heartbeat.
   */
  private case class PresenceConnection(
    ws: WebSocket,
    alive: AtomicBoolean,
    lastPong: AtomicLong,
    heartbeat: ScheduledExecutorService
  )

  /** deviceId -> active outgoing connection. */
  private val connections = new ConcurrentHashMap[String, PresenceConnection]()

  // ===== Public API =====

  /**
   * Reconcile active WS connections with a freshly scanned peer list.
   *
   * - Upserts discovered peers into the MeshService peer list.
   * - Connects to peers that are in the list but have no active WS.
   * - Disconnects from peers that have a WS but are no longer in the list.
   */
  def syncPeers(peers: List[PeerInfo]): IO[Unit] =
    val peerIds = peers.iterator.map(_.deviceId).toSet
    val staleIds =
      connections.keySet().asScala.filterNot(peerIds.contains).toList
    for
      // Upsert discovered peers into MeshService peer list
      _ <- peers.traverse_(peer => meshService.upsertPeer(peer))
      // Disconnect peers that left the tailnet
      _ <- IO.blocking(staleIds.foreach(id => disconnectPeer(id)))
      _ <- staleIds.traverse_(id => meshService.removePeer(id))
      // Connect to newly discovered peers (fire-and-forget — don't block the scan cycle)
      _ <- peers.filter(p => !connections.containsKey(p.deviceId)).traverse_(p => connect(p).start.void)
    yield ()

  /** Establish an outgoing WS presence connection to a peer. No-op if already connected. */
  def connect(peer: PeerInfo): IO[Unit] =
    if connections.containsKey(peer.deviceId) then IO.unit
    else
      extractHost(peer.address) match
        case None => IO.unit
        case Some(host) =>
          meshService.identity
            .flatMap { id =>
              // IO.blocking returns Either(errorMsg, ()) so logging happens in IO context
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
                    .proxy(java.net.ProxySelector.of(null)) // bypass HTTP proxy for Tailscale
                    .build()
                  val ws = client
                    .newWebSocketBuilder()
                    .buildAsync(URI.create(wsUri), listener)
                    .get(5, TimeUnit.SECONDS)

                  val conn = PresenceConnection(ws, alive, lastPong, heartbeat)
                  connections.put(peer.deviceId, conn)

                  // Heartbeat: send ping every 10s; close if pong overdue (> 20s)
                  heartbeat.scheduleAtFixedRate(
                    { () =>
                      try
                        if alive.get() then
                          if System.currentTimeMillis() - lastPong.get() > 20_000L then
                            logger.debugSync(s"Heartbeat timeout: ${peer.deviceName}")
                            ws.sendClose(WebSocket.NORMAL_CLOSURE, "heartbeat timeout")
                          else ws.sendText("""{"type":"ping"}""", true)
                      catch case _: Exception => ()
                    },
                    10,
                    10,
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
                case Right(_) => logger.info(s"Presence connected: ${peer.deviceName} ($host)")
                case Left(err) => logger.debug(s"Presence connect failed: ${peer.deviceName} - $err")
              }
            }
            .handleErrorWith(e => logger.debug(s"Presence connect error: ${e.getMessage}"))

  /** Explicitly disconnect from a peer by deviceId. */
  def disconnect(deviceId: String): IO[Unit] =
    IO.blocking(disconnectPeer(deviceId))

  /** Disconnect all peers (e.g. on logout). */
  def disconnectAll(): IO[Unit] =
    IO.blocking(
      connections.keySet().asScala.foreach(id => disconnectPeer(id))
    )

  // ===== Internal (called from WS listener threads) =====

  /** Close WS, shut down heartbeat, remove from map. */
  private def disconnectPeer(deviceId: String): Unit =
    val conn = connections.remove(deviceId)
    if conn != null then
      conn.alive.set(false)
      try conn.heartbeat.shutdownNow()
      catch case _: Exception => ()
      try conn.ws.sendClose(WebSocket.NORMAL_CLOSURE, "disconnect")
      catch case _: Exception => ()

  /**
   * Called by the WS listener when the connection closes or errors.
   * If the close was unexpected (alive still true), removes the peer from
   * MeshService. The next `syncPeers` cycle will reconnect if the peer is
   * still in the tailnet.
   */
  private[mesh] def onClosed(deviceId: String, peer: PeerInfo): Unit =
    val conn = connections.remove(deviceId)
    if conn != null then
      try conn.heartbeat.shutdownNow()
      catch case _: Exception => ()
      if conn.alive.get() then
        // Unexpected close — remove peer so the UI reflects it immediately
        dispatcher.unsafeRunAndForget(
          meshService.removePeer(deviceId) *>
            logger.debug(s"Presence disconnected: ${peer.deviceName}")
        )

  /** Called by the WS listener when a pong frame arrives — refreshes liveness. */
  private[mesh] def updateLastPong(deviceId: String): Unit =
    val conn = connections.get(deviceId)
    if conn != null then conn.lastPong.set(System.currentTimeMillis())

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
      "userDescription" -> id.userDescription,
      "port" -> serverPort.toString
    )
    val query = params.map((k, v) => s"$k=${enc(v)}").mkString("&")
    s"ws://$host:$serverPort/api/mesh/presence?$query"

  private def enc(s: String): String =
    try java.net.URLEncoder.encode(s, "UTF-8")
    catch case _: Exception => s
end MeshPresenceService

/**
 * JDK WebSocket.Listener for outgoing presence connections.
 * Forwards events back to MeshPresenceService — stateless on its own.
 * Uses *Sync logging because callbacks run on JDK WS threads (no IO context).
 */
private final class PresenceWsListener(
  service: MeshPresenceService,
  peer: PeerInfo
) extends WebSocket.Listener:
  private val logger = NebflowLogger.forName("nebflow.mesh.presence")

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
            case _ => ()
        case Left(_) => ()
    catch case _: Exception => ()
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
