package nebflow.neblink

import cats.effect.IO
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
  neblinkClient: Option[NeblinkClient] = None
):
  private val logger = NebflowLogger.forName("nebflow.neblink.discovery")

  /** Discovery cycle — use NebLink Server if configured. */
  def discoverCycle: IO[Unit] =
    neblinkClient match
      case Some(client) => discoverViaServer(client)
      case None => logger.warn("NebLink Server is not configured — discovery skipped")

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
          yield ()
        case Left(err) =>
          logger.warn(s"NebLink Server discovery failed: $err")
    yield ()

  /** Diagnostic scan — returns NebLink Server discovery status for debugging. */
  def diagnosticScan: IO[Json] =
    for
      currentPeers <- neblinkService.peers
      clientStatus <- neblinkClient match
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
