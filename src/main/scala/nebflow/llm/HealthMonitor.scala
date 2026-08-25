package nebflow.llm

import cats.effect.IO
import cats.effect.kernel.{Deferred, Ref}
import cats.syntax.all.*
import nebflow.core.NebflowLogger
import nebflow.shared.*

import scala.concurrent.duration.*

/** Health state of a single provider+model combination. */
enum HealthState:
  case Up
  case Down(reason: String, since: Long)

/** Health of the Tier 2a standalone search API (P2, 2026-08-25) — tracked
  * INDEPENDENTLY of model-provider health so layered health output can show
  * the decoupling the user asked for ("模型配额 DOWN ≠ 搜索 DOWN"). */
enum SearchApiHealth:
  case Unconfigured
  case Up
  case Down(reason: String, since: Long)

object ProviderHealthMonitor:
  /** Interval between background probe cycles for Down providers (2 minutes). */
  val ProbeIntervalSec = 120

  /** Timeout for a single probe request (30 seconds — thinking models are slow). */
  val ProbeTimeoutSec = 30

  /**
   * How often a [[ProviderHealthMonitor.waitForAnyUp]] waiter re-checks health
   * state when woken by a signal that did not bring its candidates Up (stale
   * or foreign-provider signal). Also the worst-case extra delay a waiter pays
   * when its blocking Deferred got replaced underneath it by another waiter.
   */
  val RecheckIntervalSec = 5

/**
 * Tracks the health of every provider+model in the candidate chain.
 *
 * Two signals drive state transitions:
 *   - Real request failures: when a candidate exhausts retries (or hits a
 *     permanent error), `markDown` is called by the LLM interface.
 *   - Background probes: every [[ProviderHealthMonitor.ProbeIntervalSec]],
 *     each Down candidate is probed via a minimal completion request.
 *     On success, `markUp` fires and wakes any request blocked in
 *     [[waitForAnyUp]].
 *
 * Additionally, when all candidates are Down and a request is about to block
 * in [[waitForAnyUp]], [[probeNow]] is called to probe the Down candidates
 * immediately instead of waiting for the next background cycle — this cuts
 * the worst-case recovery latency from ~`ProbeIntervalSec + ProbeTimeoutSec`
 * down to ~`ProbeTimeoutSec`.
 *
 * Up providers are never probed proactively — the first real request after
 * a provider comes back online will naturally discover if it is still healthy.
 */
final class ProviderHealthMonitor(registry: ProviderRegistry):
  private val logger = NebflowLogger.forName("nebflow.llm.health")

  private val statesRef: Ref[IO, Map[String, HealthState]] = Ref.unsafe(Map.empty)

  /** Deferred that completes when any provider transitions Down→Up. */
  private val signalRef: Ref[IO, Deferred[IO, Unit]] =
    Ref.unsafe(Deferred.unsafe[IO, Unit])

  /** Tier 2a standalone search API health (P2, 2026-08-25). Starts
    * Unconfigured; the first standalone search call records Up/Down. */
  private val searchHealthRef: Ref[IO, SearchApiHealth] =
    Ref.unsafe(SearchApiHealth.Unconfigured)

  /** Record a successful Tier 2a standalone search call. */
  def recordSearchSuccess(): IO[Unit] =
    searchHealthRef.set(SearchApiHealth.Up)

  /** Record a failed Tier 2a standalone search call (reason = the classified
    * diagnostic, e.g. quota/rate limited / auth failed / timeout). */
  def recordSearchFailure(reason: String): IO[Unit] =
    searchHealthRef.set(SearchApiHealth.Down(reason, System.currentTimeMillis()))

  /** Current Tier 2a search API health (P2-6 layered /api/health). */
  def getSearchHealth: IO[SearchApiHealth] =
    searchHealthRef.get

  private def key(providerId: String, model: String): String =
    s"$providerId/$model"

  /** Mark a candidate as Down. No-op if already Down. */
  def markDown(providerId: String, model: String, reason: String): IO[Unit] =
    val k = key(providerId, model)
    statesRef.get.flatMap { states =>
      states.get(k) match
        case Some(HealthState.Down(_, _)) => IO.unit
        case _ =>
          statesRef
            .set(states.updated(k, HealthState.Down(reason, System.currentTimeMillis())))
            .*>(logger.warn(s"Provider $k marked DOWN: $reason"))
    }

  /** Mark a candidate as Up and wake waiters. No-op if already Up. */
  def markUp(providerId: String, model: String): IO[Unit] =
    val k = key(providerId, model)
    statesRef.get.flatMap { states =>
      states.get(k) match
        case Some(HealthState.Down(_, _)) =>
          statesRef
            .set(states.updated(k, HealthState.Up))
            .*>(logger.info(s"Provider $k recovered (UP)"))
            .*>(signalRecovery())
        case _ => IO.unit
    }

  /** Partition candidates into (up, down) based on current health state. */
  def filterCandidates(
    candidates: List[ModelCandidate]
  ): IO[(List[ModelCandidate], List[ModelCandidate])] =
    statesRef.get.map { states =>
      candidates.partition { c =>
        states.get(key(c.providerId, c.model)) match
          case Some(HealthState.Down(_, _)) => false
          case _ => true
      }
    }

  /**
   * Block until at least one of the given candidates is Up again.
   *
   * Design (fixed after the 2026-08-15 dual-DOWN incident): health state in
   * [[statesRef]] is the source of truth; the signal Deferred is only a
   * wake-up *hint*. The old implementation consumed a one-shot Deferred —
   * the first woken waiter refreshed it, so a second concurrent waiter
   * blocked on a fresh empty Deferred even though every provider was already
   * Up, riding a ghost 120s timeout (err=120 seconds). Now every wake (signal
   * or periodic tick) re-checks [[filterCandidates]] directly: a waiter whose
   * candidates are already Up returns immediately no matter which waiter
   * consumed the signal.
   *
   * Anti-spin: when woken without our candidates being Up, we replace the
   * (possibly completed) Deferred with a fresh one and sleep one recheck
   * interval before looping — a completed Deferred would make `.get` return
   * instantly and spin the loop.
   */
  def waitForAnyUp(candidates: List[ModelCandidate]): IO[Unit] =
    def anyUp: IO[Boolean] =
      filterCandidates(candidates).map(_._1.nonEmpty)

    def loop: IO[Unit] =
      anyUp.flatMap {
        case true => IO.unit
        case false =>
          IO
            .race(
              signalRef.get.flatMap(_.get),
              IO.sleep(ProviderHealthMonitor.RecheckIntervalSec.seconds)
            )
            .flatMap { _ =>
              anyUp.ifM(
                IO.unit,
                Deferred[IO, Unit]
                  .flatMap(fresh => signalRef.tryModify(_ => (fresh, ())).void)
                  *> IO.sleep(ProviderHealthMonitor.RecheckIntervalSec.seconds)
                  *> loop
              )
            }
      }
    loop
  end waitForAnyUp

  /** Test seam: force-replace the signal Deferred (simulates anti-spin refresh orphaning a parked waiter). */
  private[llm] def replaceSignalForTest(fresh: Deferred[IO, Unit]): IO[Unit] =
    signalRef.set(fresh)

  /** Snapshot of all health states (for UI / logging). */
  def getStates: IO[Map[String, HealthState]] =
    statesRef.get

  /**
   * Candidates currently marked Down — across ALL chains, not just the default
   * preset chain. 2026-08-25 kimi incident: the probe set was previously
   * derived from `registry.getCandidates()` (default preset chain only); a
   * candidate that appears in another preset/agent chain (e.g. Vision preset's
   * kimi/k3-256k) was never probed once Down, leaving it DOWN forever (until
   * restart). Refs whose provider/model was removed from config are skipped.
   */
  private[llm] def downCandidates(): IO[List[ModelCandidate]] =
    statesRef.get.flatMap { states =>
      states.collect { case (k, HealthState.Down(_, _)) => k }.toList.flatTraverse { k =>
        registry.getCandidateForRef(k).map(_.toList)
      }
    }

  /** Start the background probing loop. Runs forever. */
  def start(): IO[Unit] =
    def loop: IO[Unit] =
      for
        downCandidates <- downCandidates()
        _ <-
          if downCandidates.nonEmpty then downCandidates.traverse_(probe)
          else IO.unit
        _ <- IO.sleep(ProviderHealthMonitor.ProbeIntervalSec.seconds)
        _ <- loop
      yield ()
    loop
  end start

  /**
   * Immediately probe the given candidates (typically the Down set) without
   * waiting for the next background cycle. Called by the LLM interface when
   * all candidates are Down and a request is about to block — this cuts the
   * worst-case recovery latency from ~`ProbeIntervalSec + ProbeTimeoutSec`
   * down to ~`ProbeTimeoutSec`.
   */
  def probeNow(candidates: List[ModelCandidate]): IO[Unit] =
    // Parallel: serial probing of N candidates cost N x ProbeTimeoutSec worst
    // case (two Down providers = up to 60s) before waiters even start waiting.
    candidates.parTraverse_(probe)

  /** Probe a single candidate with a minimal completion request. */
  private[llm] def probe(candidate: ModelCandidate): IO[Unit] =
    val params = SendMessageParams(
      messages = List(Message(MessageRole.User, Left("hi"))),
      model = candidate.model,
      // Thinking models (GLM-5.2, DeepSeek reasoning) consume tokens on
      // reasoning before producing any text content. maxTokens=1 would truncate
      // the thinking and yield empty content → probe falsely fails → provider
      // stuck DOWN forever. Use a generous cap so the probe completes.
      maxTokens = Some(4096),
      sessionId = Some("health-check"),
      agentId = Some("health-check")
    )
    registry
      .getAdapter(candidate.providerId)
      .flatMap(_.sendMessage(params))
      .timeout(ProviderHealthMonitor.ProbeTimeoutSec.seconds)
      .attempt
      .flatMap {
        case Right(_) => markUp(candidate.providerId, candidate.model)
        case Left(err) =>
          logger.debug(s"Probe failed for ${candidate.providerId}/${candidate.model}: ${err.getMessage}")
      }

  end probe

  /** Complete the current signal Deferred (wake all waiters). */
  private def signalRecovery(): IO[Unit] =
    signalRef.get.flatMap(_.complete(()).attempt.void)
end ProviderHealthMonitor
