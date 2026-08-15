package nebflow.core

import cats.effect.IO
import io.circe.syntax.given
import nebflow.llm.FallbackExhaustedError
import nebflow.shared.*

import scala.concurrent.duration.*

/**
 * First-run onboarding state machine (F3, backend slice).
 *
 * State lives in `<dataRoot>/onboarding.json`:
 *   {"state":"pending|done|skipped", "probeOkAt":<epoch-millis>?}
 *  - no file  -> None (frontend treats as pending for new users)
 *  - unparseable JSON -> (Pending, no probeOkAt) — nothing recoverable
 *  - parseable JSON with an INVALID state value -> Pending but probeOkAt
 *    is preserved (degradation must not erase the probe gate record)
 *
 * probeLlm is the HARD GATE for the guided flow (user ruling 2026-08-15),
 * now enforced SERVER-SIDE (qa follow-up): a successful probe records
 * probeOkAt, and setOnboardingState(done) is rejected unless probeOkAt
 * exists. The frontend wizard flow alone can no longer bypass the gate —
 * a raw WS setOnboardingState(done) without a prior successful probe is
 * refused.
 *
 * P0 semantics: "a probe succeeded at SOME point" is sufficient; detecting
 * config changes after the probe (config-mtime vs probeOkAt) is P1.
 */
object OnboardingService:

  sealed trait OnboardingState:
    def name: String
  object OnboardingState:
    case object Pending extends OnboardingState:
      val name = "pending"
    case object Done extends OnboardingState:
      val name = "done"
    case object Skipped extends OnboardingState:
      val name = "skipped"

    def fromString(s: String): Option[OnboardingState] = s match
      case "pending" => Some(Pending)
      case "done"    => Some(Done)
      case "skipped" => Some(Skipped)
      case _         => None

  /** Full persisted record. probeOkAt = epoch millis of the last successful LLM probe. */
  final case class StoredState(state: OnboardingState, probeOkAt: Option[Long])

  /** `def` on purpose: PathUtil.dataRoot may be swapped (tests) after object init. */
  def statePath: os.Path = PathUtil.dataRoot / "onboarding.json"

  /** Read the persisted state. None = no marker yet (fresh install). */
  def readState(): IO[Option[OnboardingState]] = readStored().map(_.map(_.state))

  /** Read the full record incl. probe gate timestamp. */
  def readStored(): IO[Option[StoredState]] = IO.blocking {
    if !os.exists(statePath) then None
    else
      io.circe.parser.parse(os.read(statePath)).toOption match
        case None => Some(StoredState(OnboardingState.Pending, None))
        case Some(json) =>
          val stateStr = json.hcursor.downField("state").as[String].toOption
          val probeOkAt = json.hcursor.downField("probeOkAt").as[Long].toOption
          // invalid/missing state degrades to Pending, but a parseable
          // probeOkAt survives the degradation
          Some(StoredState(stateStr.flatMap(OnboardingState.fromString).getOrElse(OnboardingState.Pending), probeOkAt))
  }

  /**
   * Persist a state transition (read-modify-write: probeOkAt is NEVER
   * erased by a state write). HARD GATE: Done requires a recorded
   * successful probe; returns Left with a user-actionable reason otherwise.
   */
  def setState(next: OnboardingState): IO[Either[String, OnboardingState]] =
    readStored().flatMap { current =>
      val currentProbe = current.flatMap(_.probeOkAt)
      if next == OnboardingState.Done && currentProbe.isEmpty then
        IO.pure(Left(
          "onboarding not complete: no successful LLM probe on record — call probeLlm and succeed first (配置未生效，拒绝完成引导)"
        ))
      else
        writeStored(StoredState(next, currentProbe)).as(Right(next))
    }

  /** Legacy direct write kept for internal use / tests; preserves probeOkAt. */
  def writeState(state: OnboardingState): IO[Unit] =
    readStored().flatMap { current =>
      writeStored(StoredState(state, current.flatMap(_.probeOkAt)))
    }

  private def writeStored(stored: StoredState): IO[Unit] = IO.blocking {
    os.makeDir.all(statePath / os.up)
    os.write.over(
      statePath,
      io.circe.Json.obj(
        "state" -> stored.state.name.asJson,
        "probeOkAt" -> stored.probeOkAt.asJson
      ).noSpaces
    )
  }

  /** Record a successful probe timestamp without touching the state field. */
  def recordProbeOk(): IO[Unit] =
    readStored().flatMap { current =>
      writeStored(StoredState(current.map(_.state).getOrElse(OnboardingState.Pending), Some(System.currentTimeMillis())))
    }

  // ===== LLM probe (hard gate) =====

  final case class ProbeResult(ok: Boolean, provider: Option[String], error: Option[String])

  /**
   * One real, minimal LLM call through the global LlmHandle chain (NOT an
   * agent actor turn). Success = the configured provider actually answers,
   * and records probeOkAt server-side (backend-only write; the frontend
   * needs no change and no extra call).
   * Failure = attribute per provider attempt so the user knows WHAT to fix
   * (auth key / wrong model name / unreachable endpoint).
   */
  def probeLlm(llm: LlmHandle[IO]): IO[ProbeResult] =
    val req = LlmRequest(
      messages = List(Message(MessageRole.User, Left("回复 ok"))),
      sessionId = "llm-probe",
      agentId = "llm-probe",
      maxTokens = Some(8)
    )
    llm
      .send(req)
      .flatMap { resp =>
        recordProbeOk().as(ProbeResult(ok = true, provider = Some(resp.meta.providerId), error = None))
      }
      .handleErrorWith(e => IO.pure(probeFailure(e)))
      .timeoutTo(15.seconds, IO.pure(ProbeResult(ok = false, provider = None, error = Some("timeout_15s"))))

  /** Pure: map a probe failure to a human-usable reason. */
  def probeFailure(e: Throwable): ProbeResult =
    e match
      case exhausted: FallbackExhaustedError =>
        val parts = exhausted.attempts.map { a =>
          val reason = a.reason.map(_.toString).getOrElse("unknown")
          s"${a.providerId}: $reason"
        }
        val providerHint = exhausted.attempts.headOption.map(_.providerId)
        ProbeResult(ok = false, provider = providerHint, error = Some(parts.mkString("; ")))
      case other =>
        val msg = Option(other.getMessage).getOrElse(other.getClass.getSimpleName)
        ProbeResult(ok = false, provider = None, error = Some(msg))

end OnboardingService
