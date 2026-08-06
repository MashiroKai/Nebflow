package nebflow.neblink

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.core.NebflowLogger

import scala.concurrent.duration.*

/**
 * Discovers Nebflow peers via the NebLink Server.
 *
 * Each cycle logs in (or heartbeats) to the NebLink Server to get the peer list,
 * then syncs presence connections via NeblinkPresenceService.
 *
 * Known-peer liveness is maintained entirely by WS connections (heartbeat + TCP RST).
 * The periodic discovery cycle only discovers NEW devices — it does not poll known peers.
 *
 * NebLink Server is the trust boundary: only devices on the same network can reach each other.
 */
final class NeblinkDiscovery(
  neblinkService: NeblinkService,
  serverPort: Int,
  presenceService: NeblinkPresenceService,
  initialClient: Option[NeblinkClient] = None
):
  private val logger = NebflowLogger.forName("nebflow.neblink.discovery")

  /** Consecutive heartbeat/discovery failures — drives exponential backoff. */
  private val failCount: Ref[IO, Int] = Ref.unsafe[IO, Int](0)

  /** Current backoff delay based on consecutive failures. */
  def currentDelay: IO[FiniteDuration] =
    failCount.get.map(delayForFailures)

  /** Pure backoff function — testable without IO. */
  def delayForFailures(n: Int): FiniteDuration =
    if n <= 2 then 30.seconds // first few retries at normal interval
    else if n <= 5 then 60.seconds // repeated failures → slow down
    else 120.seconds // cap at 2 minutes

  /** Reset failure counter (called on success). */
  private def resetFailCount: IO[Unit] = failCount.set(0)

  /** Increment failure counter (called on failure). */
  private def incFailCount: IO[Unit] = failCount.update(_ + 1)

  // The client is held in a Ref so it can be hot-swapped at runtime (e.g. after
  // device-flow enrollment completes, without restarting the gateway).
  private val clientRef: Ref[IO, Option[NeblinkClient]] =
    Ref.unsafe[IO, Option[NeblinkClient]](initialClient)

  /** Hot-swap the NebLink client (used after device-flow enrollment). */
  def setClient(client: Option[NeblinkClient]): IO[Unit] =
    clientRef.set(client) *> logger.info("NebLink client hot-swapped")

  /** Discovery cycle — use NebLink Server if configured. */
  def discoverCycle: IO[Unit] =
    clientRef.get.flatMap {
      case Some(client) => discoverViaServer(client)
      case None => logger.warn("NebLink Server is not configured — discovery skipped")
    }

  /**
   * Heartbeat cycle — send a heartbeat to the current client (if any).
   * Used by the periodic heartbeat loop. Returns immediately if no client.
   */
  def heartbeatCycle: IO[Unit] =
    clientRef.get.flatMap {
      case Some(client) => doHeartbeat(client)
      case None => IO.unit
    }

  private def doHeartbeat(client: NeblinkClient): IO[Unit] =
    client.heartbeat.flatMap {
      case Right(serverPeers) =>
        val neblinkPeers = client.toNeblinkPeers(serverPeers)
        val peerIps = client.peerAddresses(serverPeers)
        neblinkPeers.traverse_(p => neblinkService.upsertPeer(p)) *>
          neblinkService.updateTrustedIps(peerIps) *>
          neblinkService.sendSync(nebflow.neblink.SyncCommand.PeerDiscovered) *>
          resetFailCount
      case Left(err) =>
        // Heartbeat failed — fall back to full discovery (auto re-login if needed).
        logger.debug(s"Heartbeat failed ($err), falling back to discovery...") *>
          discoverViaServer(client).handleErrorWith(e =>
            logger.debug(s"Discovery fallback error: ${e.getMessage}") *> incFailCount
          )
    }

  /** Discovery via NebLink Server: login/heartbeat → upsert peers → sync presence. */
  private def discoverViaServer(client: NeblinkClient): IO[Unit] =
    for
      identity <- neblinkService.identity
      result <- client.discover(identity.deviceId, identity.deviceName, identity.platform, Nil)
      _ <- result match
        case Right(serverPeers) =>
          val neblinkPeers = client.toNeblinkPeers(serverPeers)
          val peerIps = client.peerAddresses(serverPeers)
          for
            _ <- neblinkPeers.traverse_(p => neblinkService.upsertPeer(p))
            _ <- neblinkService.updateTrustedIps(peerIps)
            _ <- presenceService.syncPeers(neblinkPeers)
            _ <- logger.debug(s"NebLink Server discovery: ${neblinkPeers.size} peer(s)")
            _ <- resetFailCount
          yield ()
        case Left(err) =>
          logger.warn(s"NebLink Server discovery failed: $err") *> incFailCount
    yield ()

  /** Diagnostic scan — returns NebLink Server discovery status for debugging. */
  def diagnosticScan: IO[Json] =
    for
      currentPeers <- neblinkService.peers
      currentClient <- clientRef.get
      clientStatus <- currentClient match
        case Some(client) =>
          for
            identity <- neblinkService.identity
            result <- client.discover(identity.deviceId, identity.deviceName, identity.platform, Nil)
          yield result match
            case Right(serverPeers) =>
              Json.obj(
                "configured" -> true.asJson,
                "loggedIn" -> true.asJson,
                "serverPeerCount" -> serverPeers.size.asJson
              )
            case Left(err) =>
              Json.obj(
                "configured" -> true.asJson,
                "loggedIn" -> false.asJson,
                "error" -> err.asJson
              )
        case None =>
          IO.pure(Json.obj("configured" -> false.asJson))
    yield Json.obj(
      "neblinkServer" -> clientStatus,
      "currentPeers" -> currentPeers.map(_.deviceName).asJson
    )

end NeblinkDiscovery
