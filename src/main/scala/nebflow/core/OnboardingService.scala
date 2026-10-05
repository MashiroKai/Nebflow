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
   * One real, minimal LLM call through the LlmHandle chain (NOT an agent actor
   * turn). Success = the configured provider actually answers, and records
   * probeOkAt server-side (backend-only write; the frontend needs no change and
   * no extra call).
   * Failure = attribute per provider attempt so the user knows WHAT to fix
   * (auth key / wrong model name / unreachable endpoint).
   *
   * `modelRef` = **the model the user just picked in the onboarding model step**.
   * The probe MUST measure that one (verifier round-2 finding): without it the
   * request carries no `agentModel` and the global leg resolves the seed chain
   * (`SchemePolicy.resolveModel(Nebula, None)` → first provider's first model in
   * `nebflow.json` field order), so a probe could report success while the brain
   * the user chose was dead. `modelRef` rides the EXISTING per-request point
   * (`LlmRequest.agentModel` → `ProviderRegistry.getCandidatesForAgent` →
   * `getCandidateForRef`, the same leg production agent turns use via
   * `AgentSessionExecution`), so the picked ref becomes the head of the measured
   * chain — no second resolution path is invented here.
   *
   * Carrying the ref is only half: the chain that results still ENDS with the
   * reserve tier (every other configured provider/model, appended by
   * `ProviderRegistry.getCandidatesForAgent`), so a dead pick falls through to a
   * live provider and the call would still succeed — i.e. the gate would still
   * declare success over the user's broken brain. Therefore the answer is
   * VERIFIED against the pick: when the ref that answered is not the ref that was
   * picked, the probe reports failure and records NOTHING (see [[refusedAnswer]]).
   * A probe that is asked to measure a specific model either measures it or says
   * so; it never silently measures someone else.
   *
   * `None` (a user who skipped the model question): the request carries no
   * agentModel BASICALLY, but the agent's own chain is read from disk and used
   * when it has one (see below) — a home that has a brain configured must be
   * measured on THAT brain, not on "whatever provider happens to be first in the
   * config file". Only a truly unconfigured chain falls to the global seed chain,
   * and then "whatever is configured first" really is the honest answer.
   *
   * 🔴 `onboarding.js` always sends the ref on the terminal faces (both the model
   * and the fallback face know it), so `None` here means either "the model
   * question was skipped this run" or "a legacy/out-of-band caller". For the
   * first case the pick may already be recorded on disk from an earlier run, and
   * the seed-chain fallback would then report `ok` over a brain that is not the
   * user's — the E2E cross-check showed exactly that (`ssseed` contacted while
   * the pick sat in `agents/<root>/agent.json`). Reading the own chain closes it
   * WITHOUT inventing a second resolution path: it is the same chain
   * `resolveModel` hands a follower, just also applied to Nebula (whose own chain
   * `resolveModel` deliberately ignores — that short-circuit is about the PROBE
   * surface, never about the agent's own turn model, which comes from
   * `AgentSessionExecution` reading the agent def).
   *
   * `configJson` = the current `nebflow.json` body (the same string the Settings
   * panel's store holds). It exists for ONE reason: a ref that is well-formed but
   * names a model the config does not have resolves to no candidate at all
   * (`ProviderRegistry.getCandidateForRef` returns None for an unknown id), so the
   * chain silently falls through to the reserve tier and the call succeeds with
   * SOMEONE ELSE answering — i.e. an unknown id was being reported as a success
   * for the picked model. With the config in hand the probe can tell "that model
   * exists but did not answer" (a real failure, refused by [[refusedAnswer]]) from
   * "that model does not exist" (refused up front, no network call needed).
   * Omitted (unit tests / legacy callers) ⇒ the check is skipped and behaviour is
   * exactly as before.
   */
  def probeLlm(
    llm: LlmHandle[IO],
    modelRef: Option[String] = None,
    configJson: Option[String] = None
  ): IO[ProbeResult] =
    val picked = modelRef.map(_.trim).filter(_.nonEmpty)
    // No ref ⇒ measure the agent's OWN chain when it has one (the pick from a
    // previous run, or any chain the user set in Settings); otherwise the global
    // seed chain decides, as before.
    val measured: AgentModelConfig = picked match
      case Some(ref) => AgentModelConfig(preferred = Some(ref))
      case None => ownChainOrEmpty()
    val req = LlmRequest(
      messages = List(Message(MessageRole.User, Left("回复 ok"))),
      sessionId = "llm-probe",
      agentId = "llm-probe",
      agentModel = Option.when(measured.preferred.isDefined || measured.fallbacks.nonEmpty)(measured)
    )
    picked
      .flatMap(malformedRefProblem)
      .orElse(picked.flatMap(ref => missingModelProblem(ref, configJson))) match
      case Some(err) =>
        // A ref that cannot be split, or that names a model the config does not
        // have, is not a probe target — fail loudly instead of letting the chain
        // degrade to "some other provider answered".
        IO.pure(ProbeResult(ok = false, provider = None, error = Some(err)))
      case None =>
        llm
          .send(req)
          .flatMap { resp =>
            picked.flatMap(ref => refusedAnswer(ref, resp.meta)).orElse(chainAnswerProblem(measured, resp.meta)) match
              case Some(err) =>
                IO.pure(ProbeResult(ok = false, provider = Some(resp.meta.providerId), error = Some(err)))
              case None =>
                recordProbeOk().as(ProbeResult(ok = true, provider = Some(resp.meta.providerId), error = None))
          }
          .handleErrorWith(e => IO.pure(probeFailure(e)))
          .timeoutTo(15.seconds, IO.pure(ProbeResult(ok = false, provider = None, error = Some("timeout_15s"))))

  /** The root agent's stored chain, or an empty one — never throws. */
  private def ownChainOrEmpty(): AgentModelConfig =
    try
      nebflow.core.SchemePolicy
        .ownChainOf(nebflow.core.SchemePolicy.NebulaName)
        .filter(nebflow.core.SchemePolicy.hasChain)
        .getOrElse(AgentModelConfig.empty)
    catch case _: Throwable => AgentModelConfig.empty

  /**
   * Pure: `Some(reason)` when the answer came from a candidate that is not the
   * HEAD of the chain we set out to measure. This is the no-ref arm's version of
   * [[refusedAnswer]]: with the own chain measured, the head is the user's brain,
   * so a reserve-tier cover answering is not a success for it either.
   *
   * Empty chain (nothing configured) ⇒ no verdict: there is no head to insist on.
   */
  private def chainAnswerProblem(chain: AgentModelConfig, meta: LlmMeta): Option[String] =
    chain.preferred match
      case None => None
      case Some(head) =>
        try
          val (providerId, modelId) = Config.parseModelRef(head)
          if meta.providerId == providerId && meta.model == modelId then None
          else
            Some(
              s"the configured brain ($head) did not answer — ${meta.providerId}/${meta.model} answered " +
                "instead (the fallback chain covered for it); fix that provider or pick another model"
            )
        catch case _: IllegalArgumentException => None

  /** Pure: a ref that `providerId/modelId` cannot parse (no `/`). */
  private def malformedRefProblem(ref: String): Option[String] =
    try
      Config.parseModelRef(ref)
      None
    catch
      case e: IllegalArgumentException =>
        Some(s"invalid model ref: ${Option(e.getMessage).getOrElse(ref)}")

  /**
   * Pure: `Some(reason)` when the picked ref names a model that the CURRENT
   * config does not contain (unknown provider, or a known provider without that
   * model id). Both are refs that resolve to no candidate, which the registry
   * treats as "skip" — so without this check the request quietly falls through to
   * the reserve tier and a live provider's answer is reported as a success for a
   * model that does not exist (section G4 of the E2E harness).
   *
   * `None` (no config supplied) ⇒ no verdict, the caller keeps the old behaviour.
   */
  private def missingModelProblem(ref: String, configJson: Option[String]): Option[String] =
    configJson.flatMap { raw =>
      try
        val (providerId, modelId) = Config.parseModelRef(ref)
        io.circe.parser.parse(raw).toOption.flatMap { json =>
          val providers = json.hcursor.downField("llm").downField("providers")
          if providers.downField(providerId).focus.isEmpty then
            Some(s"unknown provider '$providerId' (not in the current config)")
          else
            val modelIds = providers.downField(providerId).downField("models").focus
              .flatMap(_.asArray)
              .getOrElse(Vector.empty)
              .flatMap(_.hcursor.downField("id").as[String].toOption)
            if modelIds.contains(modelId) then None
            else Some(s"unknown model '$modelId' under provider '$providerId' (not in the current config)")
        }
      catch case _: IllegalArgumentException => None // malformed refs are malformedRefProblem's job
    }

  /**
   * Pure: `Some(reason)` when the call was answered by a candidate that is NOT
   * the ref the user picked. The chain is allowed to fall back (that is what a
   * fallback chain is for), but then the picked brain demonstrably did not
   * answer — the gate must not record a success for it.
   */
  private def refusedAnswer(ref: String, meta: LlmMeta): Option[String] =
    try
      val (providerId, modelId) = Config.parseModelRef(ref)
      if meta.providerId == providerId && meta.model == modelId then None
      else
        Some(
          s"the model you picked ($ref) did not answer — ${meta.providerId}/${meta.model} answered " +
            "instead (the fallback chain covered for it); fix that provider or pick another model"
        )
    catch case _: IllegalArgumentException => None

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
