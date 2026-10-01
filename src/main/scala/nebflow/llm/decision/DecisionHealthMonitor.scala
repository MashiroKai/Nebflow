package nebflow.llm.decision

import cats.effect.IO
import cats.effect.kernel.Ref
import io.circe.Json
import io.circe.syntax.*

/** Health state of the decision face (mirrors `SearchApiHealth`). */
enum DecisionHealth:
  case Unconfigured
  case Up
  case Down(reason: String, since: Long)

/**
 * Narrow health port of the decision face (Face A / P1-A1 adaptation layer).
 *
 * Deliberately NARROW and self-contained rather than another expansion of
 * `core.ProviderHealthPort`: the decision channel must be independent of the
 * model health face (card §A.2.8), and keeping it off the shared port leaves
 * the existing `/api/health` payload byte-identical — which is exactly what
 * the P2 golden-diff assertions on the REST payload face require. The two
 * member signatures mirror `recordSearchSuccess` / `recordSearchFailure`
 * (`core/ports.scala:188-190`) so a later batch can lift this onto the shared
 * port mechanically if the payload face is ever opened for it.
 */
trait DecisionHealthPort:
  /** Record a successful decision call. */
  def recordDecisionSuccess(): IO[Unit]

  /** Record a failed decision call (reason = classified diagnostic). */
  def recordDecisionFailure(reason: String): IO[Unit]

  /** Current decision-face health. */
  def getDecisionHealth: IO[DecisionHealth]

/**
 * In-memory decision-health monitor.
 *
 * Shape precedent = `ProviderHealthMonitor`'s search ref (`HealthMonitor.scala:67-83`):
 * a single `Ref` starting `Unconfigured`, set to `Up`/`Down` by the call site.
 * Nothing here reads a stored credential or a request body — only the
 * classified diagnostic string ever lands in the state.
 */
final class DecisionHealthMonitor extends DecisionHealthPort:

  private val ref: Ref[IO, DecisionHealth] = Ref.unsafe(DecisionHealth.Unconfigured)

  def recordDecisionSuccess(): IO[Unit] =
    ref.set(DecisionHealth.Up)

  def recordDecisionFailure(reason: String): IO[Unit] =
    ref.set(DecisionHealth.Down(reason, System.currentTimeMillis()))

  def getDecisionHealth: IO[DecisionHealth] =
    ref.get

  /** JSON view for diagnostics (same key spelling as the search channel). */
  def toJson: IO[Json] =
    ref.get.map {
      case DecisionHealth.Unconfigured => Json.obj("status" -> "unconfigured".asJson)
      case DecisionHealth.Up => Json.obj("status" -> "up".asJson)
      case DecisionHealth.Down(reason, since) =>
        Json.obj("status" -> "down".asJson, "reason" -> reason.asJson, "since" -> since.asJson)
    }

object DecisionHealthMonitor:
  def create: IO[DecisionHealthMonitor] = IO(new DecisionHealthMonitor)
