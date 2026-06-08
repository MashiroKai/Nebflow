package nebflow.mesh

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.decode
import io.circe.syntax.*
import nebflow.core.NebflowLogger

import java.net.*
import java.nio.charset.StandardCharsets

import scala.concurrent.duration.*

/**
 * UDP broadcast discovery for LAN mesh peer detection.
 *
 * Broadcasts { "type": "discover", "tokenHash": "...", "port": 8082 } every 10 seconds
 * to 255.255.255.255:19876. Listens for matching broadcasts from other devices.
 * When a matching tokenHash is heard, calls onPeerDiscovered with the sender's address.
 */
class UdpDiscovery private (
  nebflowPort: Int,
  tokenHashRef: cats.effect.Ref[IO, Option[String]],
  onPeerDiscovered: (String, Int) => IO[Unit]
):
  import UdpDiscovery.*

  private val logger = NebflowLogger.forName("nebflow.mesh.udp")

  /** Set the token hash to broadcast. Clears on unpair. */
  def setTokenHash(hash: Option[String]): IO[Unit] =
    tokenHashRef.set(hash)

  /** Start the broadcast loop. Never returns. */
  def startBroadcast: IO[Nothing] =
    tokenHashRef.get.flatMap {
      case None => IO.sleep(BroadcastInterval).flatMap(_ => startBroadcast)
      case Some(hash) =>
        IO.blocking(broadcastOnce(hash, nebflowPort))
          .handleErrorWith(e => logger.debug(s"Broadcast failed: ${e.getMessage}"))
          .flatMap(_ => IO.sleep(BroadcastInterval).flatMap(_ => startBroadcast))
    }

  /** Start the listener. Never returns. Each received packet is processed synchronously. */
  def startListen: IO[Nothing] =
    IO.blocking {
      val socket = new DatagramSocket(BroadcastPort)
      try
        val buf = new Array[Byte](4096)
        while true do
          val packet = new DatagramPacket(buf, buf.length)
          socket.receive(packet)
          val data = new String(packet.getData, packet.getOffset, packet.getLength, StandardCharsets.UTF_8)
          val senderAddr = packet.getAddress.getHostAddress
          // Process inline — we're already on a blocking thread
          processReceived(data, senderAddr).unsafeRunSync()
      finally socket.close()
    }.handleErrorWith { e =>
      logger.debug(s"Listen error: ${e.getMessage}") *> IO.sleep(2.seconds)
    }.flatMap(_ => startListen)

  private def processReceived(data: String, senderAddr: String): IO[Unit] =
    tokenHashRef.get.flatMap {
      case None => IO.unit
      case Some(myHash) =>
        IO.blocking(decode[Json](data)).flatMap {
          case Right(json) =>
            val tpe = json.hcursor.downField("type").as[String].getOrElse("")
            val remoteHash = json.hcursor.downField("tokenHash").as[String].getOrElse("")
            val remotePort = json.hcursor.downField("port").as[Int].getOrElse(8080)
            if tpe == "discover" && remoteHash == myHash then
              IO.blocking(isOwnAddress(senderAddr)).flatMap {
                case true => IO.unit
                case false =>
                  val address = s"http://$senderAddr:$remotePort"
                  logger.debug(s"Discovered peer at $address") *> onPeerDiscovered(address, remotePort)
              }
            else IO.unit
          case Left(_) => IO.unit
        }
    }

  private def broadcastOnce(tokenHash: String, port: Int): Unit =
    val socket = new DatagramSocket()
    try
      val msg = Json.obj("type" -> "discover".asJson, "tokenHash" -> tokenHash.asJson, "port" -> port.asJson)
      val bytes = msg.noSpaces.getBytes(StandardCharsets.UTF_8)
      val packet = new DatagramPacket(bytes, bytes.length, InetAddress.getByName("255.255.255.255"), BroadcastPort)
      socket.send(packet)
    finally socket.close()

end UdpDiscovery

object UdpDiscovery:
  private val BroadcastPort = 19876
  private val BroadcastInterval = 10.seconds

  /** Compute SHA-256 hash of a token (full 64-char hex string). */
  def hashToken(token: String): String =
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    digest.update(token.getBytes(StandardCharsets.UTF_8))
    digest.digest().map(b => String.format("%02x", b)).mkString

  /** Check if an IP address belongs to a local network interface. */
  def isOwnAddress(addr: String): Boolean =
    import scala.jdk.CollectionConverters.*
    NetworkInterface.getNetworkInterfaces.asScala.exists { iface =>
      iface.getInetAddresses.asScala.exists(_.getHostAddress == addr)
    }

  /** Create a UdpDiscovery instance. */
  def create(
    nebflowPort: Int,
    onPeerDiscovered: (String, Int) => IO[Unit]
  ): IO[UdpDiscovery] =
    cats.effect.Ref.of[IO, Option[String]](None).map { ref =>
      new UdpDiscovery(nebflowPort, ref, onPeerDiscovered)
    }
end UdpDiscovery
