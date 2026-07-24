package nebflow.core.flow

import cats.effect.{IO, Ref}
import cats.implicits.*

// ============================================================
// StepStatus — pipeline step lifecycle states
// ============================================================

enum StepStatus:
  case Pending, Running, Retrying, Done, Failed, Canceled

// ============================================================
// FlowMailTracker — records Mail sends between flow agents
// ============================================================

/**
 * Global registry tracking which agents sent Mail to whom.
 * PipelineActor checks this after a verdict node completes:
 *   - If the verify agent sent Mail to its retry target → FAIL
 *   - If it didn't → PASS
 *
 * This replaces the old Deferred-based verify mechanism.
 * The Mail itself is the signal — no separate type=verify needed.
 */
object FlowMailTracker:
  private val sent = Ref.unsafe[IO, Map[String, Set[String]]](Map.empty)

  /** Record that `from` sent Mail to `to`. Called by MailTool after every send. */
  def record(from: String, to: String): IO[Unit] =
    sent.update(m => m.updatedWith(from)(_.map(_ + to).orElse(Some(Set(to)))))

  /** Check if `from` sent Mail to `to`. */
  def sentTo(from: String, to: String): IO[Boolean] =
    sent.get.map(_.get(from).exists(_.contains(to)))

  /** Check if ANYONE sent Mail to `to` (regardless of sender). */
  def anySentTo(to: String): IO[Boolean] =
    sent.get.map(_.values.exists(_.contains(to)))

  /** Clear records for a sender (called after PipelineActor processes the verdict). */
  def clear(from: String): IO[Unit] =
    sent.update(_ - from)

  /** Test-only: simulate Mail sends without an actual MailTool call. */
  def recordForTest(from: String, to: String): IO[Unit] = record(from, to)

  /** Test-only: clear all records. */
  def clearAllForTest: IO[Unit] = sent.set(Map.empty)

end FlowMailTracker
