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

object ProviderHealthMonitor:
  /** Interval between background probe cycles for Down providers (2 minutes). */
  val ProbeIntervalSec = 120

  /** Timeout for a single probe request (30 seconds — thinking models are slow). */
  val ProbeTimeoutSec = 30

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
   * Block until at least one Down provider recovers.
   *
   * Uses tryGet to avoid a race where markUp fires between the caller's
   * filterCandidates check and this call — the signal would already be
   * completed, so tryGet sees Some(()) and returns immediately instead of
   * blocking on a stale Deferred.
   */
  def waitForAnyUp(): IO[Unit] =
    signalRef.get.flatMap { current =>
      current.tryGet.flatMap {
        case Some(()) =>
          // Already signaled — refresh Deferred for future waiters, return now
          Deferred[IO, Unit].flatMap { fresh =>
            signalRef.tryModify {
              case `current` => (fresh, ())
              case other => (other, ())
            }.void
          }
        case None => current.get
      }
    }

  /** Snapshot of all health states (for UI / logging). */
  def getStates: IO[Map[String, HealthState]] =
    statesRef.get

  /** Start the background probing loop. Runs forever. */
  def start(): IO[Unit] =
    def loop: IO[Unit] =
      for
        candidates <- registry.getCandidates()
        states <- statesRef.get
        downCandidates = candidates.filter(c =>
          states.get(key(c.providerId, c.model)).exists {
            case HealthState.Down(_, _) => true
            case HealthState.Up => false
          }
        )
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
    candidates.traverse_(probe)

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
