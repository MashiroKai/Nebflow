package nebflow.neblink

import cats.effect.IO
import cats.effect.kernel.Ref

/**
  * Switch-account **single-window handoff** marker (one-window switch batch,
  * 2026-09-16). Gateway memory only.
  *
  * Problem it solves: a switch-account run is a forced RP-initiated logout (the
  * provider's SSO session must really be killed, so the logout hop is a real
  * navigation to the IdP — design-required) **plus** a fresh login. The old
  * client opened the login window itself in the same gesture
  * (`neblink.js` switchLogoutAndLogin), so one switch = TWO browser windows.
  *
  * The one-window shape is: let the logout window CONTINUE into the login. The
  * window ends on the local `/auth/logged-out` landing page; when this
  * single-use marker says "this logout is a switch", that landing page answers
  * with a 302 to the authorize URL and the login runs in the same window.
  *
  * Lifecycle — one write, one read, one invalidation (all three live here; no
  * other module keeps switch-scenario state):
  *
  *  - **armed** (`arm`) — ONLY `GET /api/neblink/auth/end-session?scenario=switch`,
  *    i.e. the switch-account entry (the plain logout entry passes no scenario
  *    and therefore DISARMS — see below). Keeps the client UI language for the
  *    continuation's `ui_locales` hint (the landing hop is a bare navigation, so
  *    it cannot read the app's locale itself).
  *  - **consumed** (`consume`) — ONLY the landing route
  *    `GET /auth/logged-out`. Atomic: exactly one caller ever gets
  *    `Outcome.Continue`; the marker becomes `Consumed` in the same step, so
  *    the auto-login entry exists exactly once and cannot be replayed (a
  *    replayed/reloaded landing URL gets `Outcome.Replay` and renders a card
  *    that says so — never an auto-login).
  *  - **cleared** (`disarm`) — (i) every end-session call WITHOUT the switch
  *    scenario (so a plain logout landing can never be dragged into an
  *    auto-login by a leftover marker), (ii) every explicit login start
  *    (`POST /api/neblink/auth/start` — a fresh explicit attempt supersedes the
  *    handoff), (iii) the end-session branch where no provider is configured
  *    (no landing hop will ever come back), and (iv) `arm` itself (a new switch
  *    starts from `Armed`, never from a stale `Consumed`).
  *
  * Expiry: `Armed` older than [[NeblinkSwitchHandoff.TtlMs]] is treated as
  * absent (`Outcome.Expired`, marker cleared). The window must comfortably
  * cover the logout hop (provider page, possible confirmation click), and be
  * short enough that a forgotten marker cannot auto-login much later.
  *
  * Restart ⇒ marker lost (`Idle`) ⇒ the landing page is the plain card; the
  * client-side watchdog surfaces the login panel. Never a silent dead end.
  */
final class NeblinkSwitchHandoff private (ref: Ref[IO, NeblinkSwitchHandoff.State]):

  import NeblinkSwitchHandoff.*

  /** Arm the continuation for the switch-account flow. Replaces any previous
    * state (a new switch never inherits a spent marker). */
  def arm(uiLocales: String): IO[Unit] = armAt(uiLocales, System.currentTimeMillis())

  /** Test seam: arm with an explicit timestamp. */
  private[neblink] def armAt(uiLocales: String, now: Long): IO[Unit] =
    ref.set(Armed(now, uiLocales))

  /** Clear the marker (plain logout, explicit login start, no-provider
    * end-session). Idempotent. */
  def disarm: IO[Unit] = ref.set(Idle)

  /** Consume the marker — the landing page's read. Single-use. */
  def consume: IO[Outcome] = consumeAt(System.currentTimeMillis())

  /** Test seam: consume against an explicit timestamp (expiry tests). */
  private[neblink] def consumeAt(now: Long): IO[Outcome] =
    ref.modify {
      case Armed(at, locales) if now - at <= TtlMs => Consumed(now) -> Outcome.Continue(locales)
      case a: Armed                                => Idle -> Outcome.Expired
      case c: Consumed                             => c -> Outcome.Replay
      case Idle                                    => Idle -> Outcome.Plain
    }

  /** Read-only state name for the app-side watchdog
    * (`GET /api/neblink/auth/handoff`): `idle` | `armed` | `consumed`. Never
    * mutates — a readout must not be able to consume the handoff. */
  def stateName: IO[String] = ref.get.map {
    case Idle        => "idle"
    case _: Armed    => "armed"
    case _: Consumed => "consumed"
  }

end NeblinkSwitchHandoff

object NeblinkSwitchHandoff:

  /** Marker state. `Consumed` is kept (not folded back into `Idle`) so that a
    * replay of the landing URL is distinguishable from a plain logout landing
    * — that difference is what lets the landing page answer a replay visibly. */
  sealed trait State
  case object Idle extends State
  final case class Armed(armedAt: Long, uiLocales: String) extends State
  final case class Consumed(consumedAt: Long) extends State

  /** What the landing page must do. */
  enum Outcome:
    /** Handoff taken over: continue the login with this `ui_locales` hint. */
    case Continue(uiLocales: String)
    /** Marker was armed but is older than the TTL (cleared). */
    case Expired
    /** Marker was already consumed — this is a replay of the one-shot entry. */
    case Replay
    /** No switch handoff in play: plain-logout landing. */
    case Plain

  /** Handoff validity: the logout hop (provider page, possibly one confirmation
    * click) must fit; 10 min matches the provider's authorization-code window
    * used by [[PkceLoginSession.ExpiryMs]]. */
  val TtlMs: Long = 10 * 60 * 1000L

  def make: IO[NeblinkSwitchHandoff] =
    Ref.of[IO, State](Idle).map(new NeblinkSwitchHandoff(_))

  /** Pure-context construction — the gateway allocates route state in class
    * bodies (no IO context there). Single-threaded construction only. */
  def unsafe: NeblinkSwitchHandoff =
    new NeblinkSwitchHandoff(Ref.unsafe[IO, State](Idle))

end NeblinkSwitchHandoff
