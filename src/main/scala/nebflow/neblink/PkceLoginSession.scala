package nebflow.neblink

import cats.effect.IO
import cats.effect.kernel.Ref
import io.circe.syntax.*
import io.circe.Json

/**
  * Single-flight Authorization Code + PKCE login session (gateway memory
  * only — restart during a login simply means the user starts over).
  *
  * Lifecycle: `start` (pending) → callback `take(state)` → `succeed` /
  * `fail`. `GET /api/neblink/auth/state` reads the status; `success` /
  * `error` stay sticky until the next `start` so the frontend can render
  * the outcome after the callback page closes.
  */
final class PkceLoginSession private (
  inFlight: Ref[IO, Option[PkceLoginSession.InFlight]],
  status: Ref[IO, PkceLoginSession.Status]
):

  import PkceLoginSession.*

  /** Begin a login attempt: fresh verifier/state, status=pending. Any
    * previous attempt (or sticky result) is replaced. */
  def start(verifier: String, state: String): IO[Unit] =
    startAt(verifier, state, System.currentTimeMillis())

  /** Test seam: start with an explicit creation timestamp (expiry tests). */
  private[neblink] def startAt(verifier: String, state: String, createdAt: Long): IO[Unit] =
    inFlight.set(Some(InFlight(verifier, state, createdAt))) *>
      status.set(Status.Pending)

  /** Validate the callback's state and consume the in-flight entry. Returns
    * the verifier only on an exact match of a non-expired attempt; anything
    * else flips the status to error and returns None. */
  def take(state: String): IO[Option[String]] =
    inFlight.getAndSet(None).flatMap {
      case Some(f) if f.state == state && !expired(f) =>
        IO.pure(Some(f.verifier))
      case Some(_) =>
        status.set(Status.error("Login callback state mismatch")).as(None)
      case None =>
        // No pending attempt: browser replay / stray hit. Don't clobber a
        // sticky success — only surface an error while pending.
        status.get.flatMap {
          case Status.Pending => status.set(Status.error("No login attempt in progress")).as(None)
          case _              => IO.pure(None)
        }
    }

  /** Store the token-exchange / registration failure (callback renders it). */
  def fail(message: String): IO[Unit] = status.set(Status.error(message))

  /** Enrollment complete — sticky until the next `start`. */
  def succeed: IO[Unit] = status.set(Status.Success)

  /** Current status for the frontend poll. */
  def current: IO[Status] = status.get

  /** Status as the wire JSON: `{status: idle|pending|success|error, error?}`. */
  def statusJson: IO[Json] =
    current.map {
      case Status.Idle     => Json.obj("status" -> "idle".asJson)
      case Status.Pending  => Json.obj("status" -> "pending".asJson)
      case Status.Success  => Json.obj("status" -> "success".asJson)
      case Status.Error(m) => Json.obj("status" -> "error".asJson, "error" -> m.asJson)
    }

  /** Drop a stale pending attempt (Logto authorization codes live ~10 min;
    * the session expires slightly earlier so a late callback reads as a
    * clean error instead of a verifier mismatch). */
  private def expired(f: InFlight): Boolean =
    System.currentTimeMillis() - f.createdAt > ExpiryMs

end PkceLoginSession

object PkceLoginSession:

  final case class InFlight(verifier: String, state: String, createdAt: Long)

  sealed trait Status:
    def name: String
  object Status:
    case object Idle extends Status:
      val name = "idle"
    case object Pending extends Status:
      val name = "pending"
    case object Success extends Status:
      val name = "success"
    final case class Error(message: String) extends Status:
      val name = "error"

    def error(message: String): Status = Error(message)
  end Status

  /** Single-flight expiry: Logto's authorization code is valid for ~10 min;
    * our attempt window closes at 15 min. */
  val ExpiryMs: Long = 15 * 60 * 1000L

  def make: IO[PkceLoginSession] =
    for
      flight <- Ref.of[IO, Option[InFlight]](None)
      status <- Ref.of[IO, Status](Status.Idle)
    yield new PkceLoginSession(flight, status)

  /** Pure-context construction — the gateway allocates route state in class
    * bodies (no IO context there). Single-threaded construction only. */
  def unsafe: PkceLoginSession =
    new PkceLoginSession(
      Ref.unsafe[IO, Option[InFlight]](None),
      Ref.unsafe[IO, Status](Status.Idle)
    )

end PkceLoginSession
