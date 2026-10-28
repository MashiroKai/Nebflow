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
  private val consoleTicketFaceRef = new AtomicReference[Option[PortalHostFaces.ConsoleTicketFace]](None)
  private val sessionRevocationRef = new AtomicReference[Option[IO[Int]]](None)

  /** Bind the lifted HTTP slice (GatewayMain, inside withHttpWebSocketApp). */
  def bindHttpApp(app: HttpApp[IO]): Unit =
    httpAppRef.set(Some(app))

  /** Bind the conn-guard tunnel face (observe + check by envelope IP). */
  def bindTunnelGuard(guard: PortalHostFaces.TunnelGuard): Unit =
    tunnelGuardRef.set(Some(guard))

  /**
   * Phase 3b: bind the console-ticket face (redemption + break-glass mint +
   * unbound probe) and the session-revocation action. Both are bound by the
   * relay tunnel's constructor (the tunnel owns the ticket store the face
   * closes over), which is assembled BEFORE `connect()` fires — no late-
   * binding window, unlike the HTTP slice below.
   */
  def bindConsoleTicketFace(face: PortalHostFaces.ConsoleTicketFace): Unit =
    consoleTicketFaceRef.set(Some(face))

  /** Bind the binding-revocation action: revoke every relay-mint gateway session. */
  def bindSessionRevocation(revoke: IO[Int]): Unit =
    sessionRevocationRef.set(Some(revoke))

  /** The lifted HTTP slice, once bound. */
  def httpApp: Option[HttpApp[IO]] = httpAppRef.get

  /** The conn-guard tunnel face, once bound. */
  def tunnelGuard: Option[PortalHostFaces.TunnelGuard] = tunnelGuardRef.get

  /** The console-ticket face, once bound (consumed by the gateway's `/?ticket=` route). */
  def consoleTicketFace: Option[PortalHostFaces.ConsoleTicketFace] = consoleTicketFaceRef.get

  /** The session-revocation action, once bound (consumed by the two binding-dead signals). */
  def sessionRevocation: Option[IO[Int]] = sessionRevocationRef.get

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

  /**
   * Phase 3b: the console-ticket face as a port (same shape discipline as
   * TunnelGuard — function members only, no concrete classes, so the gateway
   * consumes the face without a neblink→gateway type dependency):
   *  - `isUnbound` — the binding-gate probe the redemption face applies to
   *    relay-mint tickets (local-tickets are exempt: break-glass);
   *  - `verifyAndConsume` — the atomic one-shot redemption;
   *  - `mintLocalTicket` — the break-glass mint (origin=local-ticket,
   *    binding gate EXEMPT, budget gate still enforced; 120s TTL).
   */
  final case class ConsoleTicketFace(
    isUnbound: IO[Boolean],
    verifyAndConsume: String => IO[Either[String, ConsoleTicket]],
    mintLocalTicket: IO[Either[String, IssuedConsoleTicket]]
  )

end PortalHostFaces

/**
 * Process-global dependencies of the portal bridge, owned by the relay
 * tunnel: the late-bound faces, the console ticket store, the binding state
 * machine, the live device identity (evaluated per mint so enrollment
 * hot-swaps are picked up, mirroring the tokenGetter discipline), and the
 * gateway-side revocation port for nf path-read tickets (phase 3b; injected
 * by GatewayMain from the SAME NfTicketStore the /api face holds — never a
 * second instance).
 */
private[neblink] final class PortalTunnelDeps(
  val faces: PortalHostFaces,
  val consoleTickets: ConsoleTicketStore,
  val bindingState: PortalBindingState,
  val identity: IO[DeviceIdentity],
  val revokeNfTickets: IO[Int]
):

  /**
   * The SINGLE mint policy chain, shared by both mint points (plan §3.4):
   * gate check -> budget consume -> identity -> issue. The gates are a
   * PARAMETERIZED SUBSET, not two implementations:
   *  - relay mint (`enforceBindingGate = true`): an unbound binding refuses
   *    the mint (`unbound`), and the shared budget still applies;
   *  - local break-glass mint (`enforceBindingGate = false`): the binding
   *    gate is exempt — the escape face must work in the revoked state
   *    (v3 :290) — but the SAME budget applies (no scripted minting either
   *    way).
   * `Left` carries the wire error enum (`unbound` / `rate_limited`).
   */
  def mintConsoleTicket(
    accountSub: String,
    origin: String,
    enforceBindingGate: Boolean
  ): IO[Either[String, IssuedConsoleTicket]] =
    val gate: IO[Boolean] =
      if enforceBindingGate then bindingState.isUnbound else IO.pure(false)
    gate.flatMap { unbound =>
      if unbound then IO.pure(Left("unbound"))
      else
        consoleTickets.tryConsumeMintBudget().flatMap {
          case false => IO.pure(Left("rate_limited"))
          case true =>
            val ttl =
              if origin == ConsoleTicketStore.OriginLocalTicket then ConsoleTicketStore.LocalTicketTtlSeconds
              else ConsoleTicketStore.RelayMintTtlSeconds
            identity.flatMap { id =>
              consoleTickets.issue(accountSub, id.deviceId, origin, Some(ttl)).map(Right(_))
            }
        }
    }

end PortalTunnelDeps
