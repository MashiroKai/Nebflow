package nebflow.neblink

import cats.effect.{IO, Ref}
import io.circe.parser.decode
import io.circe.syntax.*
import nebflow.core.AtomicJson
import nebflow.shared.{NebflowLogger, PathUtil}

/**
 * Host-side account-binding state machine — minimal phase 3a face
 * (design section 3.5): `bound -> unbound`, driven by
 * `BindingControl{kind: revoked}` (terminal judgment table row 1) or by the
 * upgrade gate's 401 terminal body (row 2). Phase 3b extends this with
 * full-session revocation; the `onRevoked` extension point lives with the
 * bridge that owns the ticket store.
 *
 * Persistence: `<dataRoot>/neblink/binding-state.json`, written through
 * [[AtomicJson]] (tmp + ATOMIC_MOVE). The kicked-park latch taught the lesson
 * this file exists to keep: in-process-only state forgets on restart, so the
 * terminal state MUST survive a process restart — otherwise an unbound host
 * silently re-enters the network on the next boot.
 *
 * Row 4 of the judgment table (upgrade success clears the terminal state) is
 * implemented by the tunnel's connect path calling [[clearTerminal]].
 */
object PortalBindingState:

  sealed trait Status
  case object Bound extends Status
  case object Unbound extends Status

  private val logger = NebflowLogger.forName("nebflow.neblink.relay")

  private def stateFile: os.Path = PathUtil.dataRoot / "neblink" / "binding-state.json"

  private final case class Persisted(status: String, updatedAt: Long)
  private object Persisted:
    // Hand-rolled codec (two fields) — no circe-generic derivation needed.
    def toJson(p: Persisted): io.circe.Json =
      io.circe.Json.obj("status" -> p.status.asJson, "updatedAt" -> p.updatedAt.asJson)
    def fromJson(j: io.circe.Json): Option[Persisted] =
      for
        status <- j.hcursor.get[String]("status").toOption
        updatedAt <- j.hcursor.get[Long]("updatedAt").toOption
      yield Persisted(status, updatedAt)

  /** Load the persisted state (missing/corrupt file = the pre-3a default, bound). */
  private def loadPersisted(): Status =
    try
      val f = stateFile
      if os.exists(f) then
        decode[io.circe.Json](os.read(f)).toOption.flatMap(Persisted.fromJson) match
          case Some(p) if p.status == "unbound" => Unbound
          case _                                => Bound
      else Bound
    catch
      case _: Exception =>
        Bound

  def create(): IO[PortalBindingState] =
    IO.blocking(loadPersisted()).flatMap { initial =>
      Ref.of[IO, Status](initial).map(new PortalBindingState(_))
    }

  def unsafeCreate(): PortalBindingState =
    new PortalBindingState(Ref.unsafe(loadPersisted()))

end PortalBindingState

class PortalBindingState private (statusRef: Ref[IO, PortalBindingState.Status]):
  import PortalBindingState.*

  def status: IO[Status] = statusRef.get

  def isUnbound: IO[Boolean] = status.map(_ == Unbound)

  /**
   * Row 1 / row 2 entry: set the terminal state and persist it. Idempotent;
   * returns whether this call actually transitioned (false = already
   * unbound). Persistence failure is logged and swallowed — the in-process
   * state still flips, and the next transition retries the write.
   */
  def markUnbound(): IO[Boolean] =
    statusRef.modify {
      case Unbound => (Unbound, false)
      case Bound   => (Unbound, true)
    }.flatMap { transitioned =>
      IO.whenA(transitioned)(persist("unbound")).as(transitioned)
    }

  /**
   * Row 4: a successful upgrade (101, no terminal code) clears the terminal
   * state. Returns whether a terminal state was actually cleared. With the
   * server-side binding gate not yet deployed this can clear on the first
   * reconnect after an unbind; the gate (design section 2.6) makes the 401
   * terminal body the authoritative hold. Deployment-order hard constraint:
   * the gate lands before / with this row.
   */
  def clearTerminal(): IO[Boolean] =
    statusRef.modify {
      case Bound   => (Bound, false)
      case Unbound => (Bound, true)
    }.flatMap { cleared =>
      IO.whenA(cleared)(persist("bound")).as(cleared)
    }

  private def persist(statusValue: String): IO[Unit] =
    IO.realTime.map(_.toMillis).flatMap { now =>
      AtomicJson.write(stateFile, Persisted.toJson(Persisted(statusValue, now)).spaces2)
    }.handleErrorWith { e =>
      logger.warn(
        s"binding state persistence failed (status=$statusValue): ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}"
      )
    }

end PortalBindingState
