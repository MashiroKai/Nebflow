package nebflow.mesh

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.decode
import io.circe.syntax.*
import nebflow.core.NebflowLogger
import sttp.client4.{DefaultSyncBackend, asStringAlways, basicRequest}

import scala.concurrent.duration.*

/**
 * Discovers Nebflow peers on the Tailscale network.
 *
 * Two-phase design per cycle:
 *   1. Scan: `tailscale status` → probe each active peer's gateway → build peer list.
 *   2. Announce: push our device info to every discovered peer so they know we're online.
 *
 * This replaces the cloud relay discovery entirely. Tailscale is the trust boundary:
 * only devices on the same tailnet can reach each other's gateway.
 */
final class TailscaleDiscovery(
  meshService: MeshService,
  serverPort: Int
):
  private val logger = NebflowLogger.forName("nebflow.mesh.tailscale")
  private val backend = DefaultSyncBackend()

  /** One discovery cycle: scan tailnet → update peers → announce to all. */
  def discoverCycle: IO[Unit] =
    for
      peers <- scanTailnet
      _ <- meshService.updatePeers(peers)
      _ <- peers.traverse_(announceTo)
      _ <- logger.debug(s"Discovery cycle complete: ${peers.size} Nebflow peer(s)")
    yield ()

  // ===== Scan =====

  /**
   * Run `tailscale status`, parse active peers, probe each for a Nebflow gateway.
   * Returns peers whose gateway responded with valid device info.
   */
  private def scanTailnet: IO[List[PeerInfo]] =
    getTailscalePeers.flatMap { entries =>
      entries
        .traverse { entry =>
          probeNebflow(entry.ip).map(_.map { info =>
            PeerInfo(
              deviceId = info.deviceId,
              deviceName = info.deviceName,
              platform = info.platform,
              address = s"http://${entry.ip}:$serverPort",
              capabilities = info.capabilities,
              userDescription = info.userDescription
            )
          })
        }
        .map(_.flatten)
    }

  /**
   * Execute `tailscale status` and parse the text output.
   * Returns active peers (excluding self). Gracefully returns Nil if Tailscale
   * is not installed or not running.
   */
  private def getTailscalePeers: IO[List[TailscaleEntry]] =
    IO.blocking {
      val binary = findTailscaleBinary
      if binary.isEmpty then
        logger.warn("tailscale binary not found — discovery skipped")
        Nil
      else
        var proc: Process = null
        try
          proc = new ProcessBuilder(binary.get, "status").redirectErrorStream(true).start()
          val is = proc.getInputStream
          try
            val output = scala.io.Source.fromInputStream(is).mkString
            if !proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) then
              proc.destroyForcibly()
              Nil
            else parseStatusOutput(output)
          finally is.close()
        catch
          case e: Exception =>
            logger.warn(s"tailscale status failed: ${e.getMessage}")
            Nil
      end if
    }

  /** Find the tailscale binary — try PATH first, then common absolute paths. */
  private def findTailscaleBinary: Option[String] =
    val candidates =
      if System.getProperty("os.name").toLowerCase.contains("win") then
        List(
          "tailscale.exe",
          "C:\\Program Files\\Tailscale\\tailscale.exe",
          "C:\\Program Files (x86)\\Tailscale\\tailscale.exe"
        )
      else List("tailscale", "/usr/local/bin/tailscale", "/opt/homebrew/bin/tailscale", "/usr/bin/tailscale")
    candidates.find { cmd =>
      try
        val p = new ProcessBuilder(cmd, "version").redirectErrorStream(true).start()
        val exited = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
        if !exited then p.destroyForcibly()
        val ok = exited && p.exitValue() == 0
        p.getInputStream.close()
        ok
      catch case _: Exception => false
    }

  end findTailscaleBinary

  /** Parse `tailscale status` text output into peer entries. */
  private def parseStatusOutput(output: String): List[TailscaleEntry] =
    output.linesIterator
      .filter(_.nonEmpty)
      .drop(1) // first line is always self
      .flatMap { line =>
        val parts = line.split("\\s+")
        if parts.length >= 4 then
          val ip = parts(0)
          if ip.startsWith("100.") then Some(TailscaleEntry(ip))
          else None
        else None
      }
      .toList

  /** Probe a peer's gateway to check if it's a Nebflow instance. */
  private def probeNebflow(ip: String): IO[Option[DeviceDiscoveryInfo]] =
    IO.blocking {
      try
        val resp = basicRequest
          .get(sttp.model.Uri.unsafeParse(s"http://$ip:$serverPort/api/mesh/discover"))
          .readTimeout(3.seconds)
          .response(asStringAlways)
          .send(backend)
        if resp.code.isSuccess then decode[DeviceDiscoveryInfo](resp.body).toOption
        else None
      catch case _: Exception => None
    }.handleErrorWith(_ => IO.pure(None))

  // ===== Announce =====

  /** Send our device info to a peer so they add us to their peer list immediately. */
  private def announceTo(peer: PeerInfo): IO[Unit] =
    meshService.identity.flatMap { id =>
      IO.blocking {
        try
          val body = Json.obj(
            "deviceId" -> id.deviceId.asJson,
            "deviceName" -> id.deviceName.asJson,
            "platform" -> id.platform.asJson,
            "capabilities" -> id.capabilities.asJson,
            "userDescription" -> id.userDescription.asJson,
            "port" -> serverPort.asJson
          )
          basicRequest
            .post(sttp.model.Uri.unsafeParse(s"${peer.address}/api/mesh/announce"))
            .contentType("application/json")
            .body(body.noSpaces)
            .readTimeout(5.seconds)
            .response(asStringAlways)
            .send(backend)
          ()
        catch case _: Exception => ()
      }.handleErrorWith(_ => IO.unit)
    }

  private case class TailscaleEntry(ip: String)
end TailscaleDiscovery
