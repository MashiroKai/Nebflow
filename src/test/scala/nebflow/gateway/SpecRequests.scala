package nebflow.gateway

import cats.effect.IO
import com.comcast.ip4s.{IpAddress, Ipv4Address, Ipv6Address, Port, SocketAddress}
import org.http4s.Request

/**
 * Test-support request plumbing for the stage C origin gate (plan §2.4):
 * in-process `Request[IO]` constructors carry NO connection info, so
 * `req.remote` is None — which reads as TUNNEL origin once the gate is live.
 * Route-level specs re-anchor their request builders with
 * [[directLoopback]] (the pre-stage-C default: a direct client on 127.0.0.1)
 * and use the other addresses here to drive the tunnel / LAN / public arms
 * of the origin matrix.
 */
private[gateway] object SpecRequests:

  /** Arbitrary fixed port — the gate reads only the host part of `remote`. */
  private val SpecPort: Port = Port.fromInt(61000).get

  private def addr(ip: String): SocketAddress[IpAddress] =
    SocketAddress(IpAddress.fromString(ip).getOrElse(sys.error(s"bad spec ip: $ip")), SpecPort)

  /** Direct loopback client, v4 — the historical in-repo default remote. */
  val LoopbackV4: SocketAddress[IpAddress] = addr("127.0.0.1")

  /** Direct loopback client, v6 (::1). */
  val LoopbackV6: SocketAddress[IpAddress] = SocketAddress(Ipv6Address.Loopback, SpecPort)

  /** Direct v4-mapped loopback (what dual-stack sockets report for v4 peers). */
  val LoopbackMapped: SocketAddress[IpAddress] = addr("::ffff:127.0.0.1")

  /** Direct LAN client (RFC1918) — rejected by the pure-loopback gate. */
  val Lan: SocketAddress[IpAddress] = addr("192.168.1.50")

  /** Direct public-IP client — rejected by the pure-loopback gate. */
  val Public: SocketAddress[IpAddress] = addr("203.0.113.7")

  /**
   * Attach `remote` to an in-process request via the http4s ConnectionInfo
   * vault key — the same slot the ember server fills from the socket.
   * `None` leaves the request remote-less: exactly the tunnel bridge's
   * in-process HTTP shape.
   */
  def withRemote(r: Request[IO], remote: Option[SocketAddress[IpAddress]]): Request[IO] =
    remote.fold(r)(a => r.withAttribute(Request.Keys.ConnectionInfo, Request.Connection(LoopbackV4, a, secure = false)))

end SpecRequests
