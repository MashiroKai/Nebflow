package nebflow.core

import cats.effect.IO
import io.circe.syntax.given
import nebflow.llm.FallbackExhaustedError
import nebflow.shared.*

import scala.concurrent.duration.*

/**
 * First-run onboarding state machine (F3, backend slice).
 *
 * State lives in `<dataRoot>/onboarding.json` as `{"state":"pending|done|skipped"}`:
 *  - no file  -> None (frontend treats as pending for new users)
 *  - bad JSON -> treated as pending (corrupt state must never brick the wizard)
 *
 * probeLlm is the HARD GATE for the guided flow (user ruling 2026-08-15):
 * the welcome message may only be sent after a REAL LLM call succeeds —
 * a configured-but-broken provider must not produce a dead first chat.
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

  /** `def` on purpose: PathUtil.dataRoot may be swapped (tests) after object init. */
  def statePath: os.Path = PathUtil.dataRoot / "onboarding.json"

  /**
   * Read the persisted state. None = no marker yet (fresh install).
   * Unreadable/corrupt file degrades to Some(Pending) rather than failing —
   * the wizard is recoverable, a crash is not.
   */
  def readState(): IO[Option[OnboardingState]] = IO.blocking {
    if !os.exists(statePath) then None
    else
      val parsed = io.circe.parser.parse(os.read(statePath)).toOption
      val stateStr = parsed.flatMap(_.hcursor.downField("state").as[String].toOption)
      stateStr.flatMap(OnboardingState.fromString).orElse(Some(OnboardingState.Pending))
  }

  def writeState(state: OnboardingState): IO[Unit] = IO.blocking {
    os.makeDir.all(statePath / os.up)
    os.write.over(statePath, io.circe.Json.obj("state" -> state.name.asJson).noSpaces)
  }

  // ===== LLM probe (hard gate) =====

  final case class ProbeResult(ok: Boolean, provider: Option[String], error: Option[String])

  /**
   * One real, minimal LLM call through the global LlmHandle chain (NOT an
   * agent actor turn). Success = the configured provider actually answers.
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
      .map(resp => ProbeResult(ok = true, provider = Some(resp.meta.providerId), error = None))
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
