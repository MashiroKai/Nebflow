package nebflow.core

import cats.effect.IO
import io.circe.syntax.given
import nebflow.shared.{FallbackExhaustedError, *}

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
      case "done" => Some(Done)
      case "skipped" => Some(Skipped)
      case _ => None
  end OnboardingState

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

  // ============================================================
  // 问卷答案（personal-agent 批 2026-10-04）
  // ============================================================

  /**
   * 答案在 `onboarding.json` 里的键名。
   *
   * 与 `state` / `probeOkAt` **同文件**（而不是另起一个 `onboarding-answers.json`）：
   * 问卷是一次性状态的三个面（走到哪、探测成功过没有、答了什么），拆文件会让
   * 「重跑 onboarding」的清空动作变成两处、漏一处就残留半套答案。
   */
  private val AnswersKey = "answers"

  /** 读答案（无文件 / 无该键 ⇒ 空表）。 */
  def readAnswers(): IO[Map[String, io.circe.Json]] = IO.blocking {
    readStoredJson().flatMap(_.hcursor.downField(AnswersKey).focus.flatMap(_.asObject)).map(_.toMap).getOrElse(Map.empty)
  }

  /**
   * 合并写入答案（**逐题 upsert**，不是整表覆盖）：一题一卡、逐题推进的问卷里，
   * 前端每题 resolve 后就落一次；整表覆盖会把前面已答的题抹掉（重放路径尤甚）。
   *
   * `state` / `probeOkAt` 一律保留（读-改-写，与 [[setState]] 同款纪律）。
   */
  def mergeAnswers(patch: Map[String, io.circe.Json]): IO[Unit] = IO.blocking {
    val current = readStoredJson().getOrElse(io.circe.Json.obj())
    val existing = current.hcursor.downField(AnswersKey).focus.flatMap(_.asObject).map(_.toMap).getOrElse(Map.empty)
    val merged = io.circe.Json.obj((existing ++ patch).toSeq.map((k, v) => k -> v)*)
    AtomicJson.writeSync(statePath, current.deepMerge(io.circe.Json.obj(AnswersKey -> merged)).noSpaces)
  }

  /** 清空答案（重跑 onboarding 用；保留 state / probeOkAt）。 */
  def clearAnswers(): IO[Unit] = IO.blocking {
    val current = readStoredJson().getOrElse(io.circe.Json.obj())
    AtomicJson.writeSync(
      statePath,
      current.asObject.map(o => io.circe.Json.fromJsonObject(o.remove(AnswersKey))).getOrElse(current).noSpaces
    )
  }

  private def readStoredJson(): Option[io.circe.Json] =
    if !os.exists(statePath) then None
    else io.circe.parser.parse(os.read(statePath)).toOption

  /**
   * Persist a state transition (read-modify-write: probeOkAt is NEVER
   * erased by a state write). HARD GATE: Done requires a recorded
   * successful probe; returns Left with a user-actionable reason otherwise.
   */
  def setState(next: OnboardingState): IO[Either[String, OnboardingState]] =
    readStored().flatMap { current =>
      val currentProbe = current.flatMap(_.probeOkAt)
      if next == OnboardingState.Done && currentProbe.isEmpty then
        IO.pure(
          Left(
            "onboarding not complete: no successful LLM probe on record — call probeLlm and succeed first (配置未生效，拒绝完成引导)"
          )
        )
      else writeStored(StoredState(next, currentProbe)).as(Right(next))
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
      io.circe.Json
        .obj(
          "state" -> stored.state.name.asJson,
          "probeOkAt" -> stored.probeOkAt.asJson
        )
        .noSpaces
    )
  }

  /** Record a successful probe timestamp without touching the state field. */
  def recordProbeOk(): IO[Unit] =
    readStored().flatMap { current =>
      writeStored(
        StoredState(current.map(_.state).getOrElse(OnboardingState.Pending), Some(System.currentTimeMillis()))
      )
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
      agentId = "llm-probe"
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
