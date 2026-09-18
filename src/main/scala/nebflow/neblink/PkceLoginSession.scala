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
        status.set(Status.of(CredentialFailure.CallbackStateInvalid)).as(None)
      case None =>
        // No pending attempt: browser replay / stray hit. Don't clobber a
        // sticky success — only surface an error while pending.
        status.get.flatMap {
          case Status.Pending => status.set(Status.of(CredentialFailure.CallbackStateInvalid)).as(None)
          case _              => IO.pure(None)
        }
    }

  /** Store the token-exchange / registration failure (callback renders it).
    *
    * 🔴 缺陷 A（上游 §8.2 第 5 项）：失败**必须**走分类 —— `fail(message)` 是兼容面
    * （自由串经兜底分类进城，原文进**日志**留档、不进用户可见面）；新调用点用
    * [[failClassified]] / [[failDiagnosed]]。 */
  def fail(message: String): IO[Unit] =
    val diagnostic = CredentialDiagnostics.diagnosticOf(CredentialFailure.Unclassified, message)
    logger.warn(diagnostic.logLine("pkce login failure (unclassified caller)"), "code" -> diagnostic.code) *>
      status.set(Status.Error(diagnostic))

  /** Classified failure (缺陷 A): the sticky error now carries a stable code +
    * human reason + next action, so `/auth/state` is machine-readable and the
    * UI never has to print a raw backend string. */
  def failClassified(failure: CredentialFailure, detail: String = ""): IO[Unit] =
    status.set(Status.Error(CredentialDiagnostics.diagnosticOf(failure, detail)))

  /** Sticky, structured failure (fulfilled form — the diagnostic is built by the
    * caller, e.g. from a `Left` channel that already classified it). */
  def failDiagnosed(diagnostic: CredentialDiagnostics.Diagnostic): IO[Unit] =
    status.set(Status.Error(diagnostic))

  /** Enrollment complete — sticky until the next `start`. */
  def succeed: IO[Unit] = status.set(Status.Success)

  /** Current status for the frontend poll. */
  def current: IO[Status] = status.get

  /** Status as the wire JSON: `{status: idle|pending|success|error, error?, code?,
    * reason?, action?}` — the three error keys are **additive** (缺陷 A): old
    * consumers ignore unknown fields and keep reading `error`, which now carries
    * the three-part sentence instead of a raw backend string. */
  def statusJson: IO[Json] =
    current.map {
      case Status.Idle     => Json.obj("status" -> "idle".asJson)
      case Status.Pending  => Json.obj("status" -> "pending".asJson)
      case Status.Success  => Json.obj("status" -> "success".asJson)
      case Status.Error(d) => Json.obj("status" -> "error".asJson).deepMerge(d.toJson)
    }

  /** Drop a stale pending attempt. Logto authorization codes live ~10 min and
    * our window (15 min) intentionally outlives them: a late callback fails
    * in Logto's token exchange (code expired) rather than the local verifier
    * check — either way the user gets a clean error, never a hung pending
    * state. */
  private def expired(f: InFlight): Boolean =
    System.currentTimeMillis() - f.createdAt > ExpiryMs

end PkceLoginSession

object PkceLoginSession:

  private val logger = nebflow.core.NebflowLogger.forName("nebflow.neblink.pkce")

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
    /** Structured error carrier (缺陷 A / 上游 §8.2 第 5 项)：**不再**是裸串 ——
      * 分类码 + 人话原因 + 下一步动作由 `CredentialDiagnostics` 单点给出。 */
    final case class Error(diagnostic: CredentialDiagnostics.Diagnostic) extends Status:
      val name = "error"

    /** 分类形态的构造（唯一推荐入口）。 */
    def of(failure: CredentialFailure, detail: String = ""): Status =
      Error(CredentialDiagnostics.diagnosticOf(failure, detail))

    /** 兼容面（老调用点/测试用自由串）：经兜底分类进城，串本身逐字保留为诊断细节。 */
    def error(message: String): Status =
      Error(CredentialDiagnostics.diagnosticOf(CredentialFailure.Unclassified, message))
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
