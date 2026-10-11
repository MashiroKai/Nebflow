package nebflow.neblink

import cats.effect.IO
import org.http4s.HttpApp

import java.util.concurrent.atomic.AtomicReference

/**
 * Late-bound, process-global faces the portal tunnel bridge consumes
 * (phase 3a design sections 3.1/3.2).
 *
 * WHY late-bound: the relay tunnel object is assembled BEFORE the ember
 * server builder runs (the tunnel connects at boot and idles until a server
 * URL appears), while the lifted HTTP slice only exists inside
 * `withHttpWebSocketApp`. GatewayMain therefore creates this holder before
 * the tunnel and binds the faces once the HTTP app is assembled — the same
 * late-binding pattern as `wsRoutesHolder`.
 *
 * The HTTP slice bound here is `Router(...).orNotFound` INCLUDING the gzip
 * static tree and EXCLUDING the outer `ConnGuard.requestTap`: requestTap is
 * count-only for the direct face, and the portal bridge counts tunnel
 * requests itself through the tunnel guard port below — wrapping both faces
 * would double-count every tunneled request.
 *
 * The tunnel guard is a PORT (two functions), not a `ConnGuard` reference:
 * `ConnGuard` lives in `nebflow.gateway`; depending on the concrete class
 * from `nebflow.neblink` would couple the relay client layer to the gateway
 * server assembly. GatewayMain binds the port to the real guard.
 */
final class PortalHostFaces(val gatewayPort: Int):

  private val httpAppRef = new AtomicReference[Option[HttpApp[IO]]](None)
  private val tunnelGuardRef = new AtomicReference[Option[PortalHostFaces.TunnelGuard]](None)

  /** Bind the lifted HTTP slice (GatewayMain, inside withHttpWebSocketApp). */
  def bindHttpApp(app: HttpApp[IO]): Unit =
    httpAppRef.set(Some(app))

  /** Bind the conn-guard tunnel face (observe + check by envelope IP). */
  def bindTunnelGuard(guard: PortalHostFaces.TunnelGuard): Unit =
    tunnelGuardRef.set(Some(guard))

  /** The lifted HTTP slice, once bound. */
  def httpApp: Option[HttpApp[IO]] = httpAppRef.get

  /** The conn-guard tunnel face, once bound. */
  def tunnelGuard: Option[PortalHostFaces.TunnelGuard] = tunnelGuardRef.get

end PortalHostFaces

object PortalHostFaces:

  /**
   * The conn-guard tunnel face as a port: count one tunneled request for the
   * envelope IP (`observe`), then read the admission verdict (`check`;
   * `Some(reason)` = over threshold). Key = envelope IP — the single-value
   * `x-forwarded-for` the ingress writes from the trusted proxy-injected
   * X-Real-IP — never the ingress socket address (which is always the proxy
   * container's). The loopback exemption NEVER applies on this face.
   */
  final case class TunnelGuard(
    observe: String => IO[Unit],
    check: String => IO[Option[String]]
  )

end PortalHostFaces

/**
 * Process-global dependencies of the portal bridge, owned by the relay
 * tunnel: the late-bound faces, the console ticket store, the binding state
 * machine, and the live device identity (evaluated per mint so enrollment
 * hot-swaps are picked up, mirroring the tokenGetter discipline).
 */
private[neblink] final class PortalTunnelDeps(
  val faces: PortalHostFaces,
  val consoleTickets: ConsoleTicketStore,
  val bindingState: PortalBindingState,
  val identity: IO[DeviceIdentity]
)
