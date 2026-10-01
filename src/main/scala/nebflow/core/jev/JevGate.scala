package nebflow.core.jev

import io.circe.Json

/**
 * The coexistence gate (JeV integration Face C / P2, per the P0 #3 posture).
 *
 * ==What this file is==
 * ONE pure decision point answering "does the JeV allocation face apply to
 * this dispatch?". Keeping it pure (no IO, no config read, no globals) is the
 * point: it makes "OFF means the existing code path" a STRUCTURAL property
 * rather than a scattering of `if` statements, exactly as
 * `PluginDispatchPolicy.effective` does for the plugin dispatch face.
 *
 * The gate never reads configuration itself. The caller resolves a
 * [[JevGateSnapshot]] (hot read, per dispatch — the `PluginsConfig.enabled`
 * precedent) and hands it in. That keeps the decision reproducible and
 * unit-testable without touching the filesystem.
 *
 * ==Author's third sentence, mechanically==
 * "Keep both systems; JeV applies only when configured AND the toggle is on."
 * `applied = configured && enabled` — either one missing means the current
 * path, byte for byte.
 */
final case class JevGateSnapshot(
  /** The `jev` block exists at all. */
  configured: Boolean,
  /** The `jev.enabled` toggle. */
  enabled: Boolean,
  /** Resolved provider id ("" when unconfigured). */
  provider: String,
  /** Allocation-call deadline (ms). See the axis warning on [[JevGate]]. */
  timeoutMs: Long,
  /** High-cardinality choice policy ("" when unset). */
  choiceHighBasePolicy: String
)

object JevGateSnapshot:

  /** The snapshot of an absent/disabled face — resolves to OFF. */
  val Off: JevGateSnapshot =
    JevGateSnapshot(configured = false, enabled = false, provider = "", timeoutMs = 0L, choiceHighBasePolicy = "")

/**
 * Why the face is not applied.
 *
 * A typed reason rather than a boolean, because the three cases have
 * DIFFERENT operational meanings (an operator debugging "why is JeV not
 * allocating" needs to tell "switched off" from "half-configured"), while all
 * three lead to the same legacy path.
 */
enum JevGateReason:
  case NotConfigured
  case ToggleOff
  case ProviderMissing

  def message: String = this match
    case NotConfigured => "jev block absent"
    case ToggleOff => "jev.enabled is off"
    case ProviderMissing => "jev.provider is empty or unknown"

/** Outcome of the gate: either the face applies, or a typed reason why not. */
enum JevGateOutcome:
  case Applied(snapshot: JevGateSnapshot)
  case Off(reason: JevGateReason)

object JevGate:

  /** Provider ids the gate accepts (mirrors `llm.decision.DecisionProvider`).
    *
    * Duplicated as literals ON PURPOSE: `core` must not import `llm` (the
    * dependency direction is `shared <- core <- cli/gateway`). The duplication
    * is pinned by a spec that asserts the two lists agree, so drift fails a
    * test instead of silently changing behaviour. */
  val KnownProviders: List[String] = List("typesafe-jev", "laya")

  /**
   * The single decision point.
   *
   * 🔴 `timeoutMs` AXIS WARNING: the deadline carried here is the
   * ALLOCATION-CALL budget (how long dispatch waits for a decision before
   * falling back). It is a DIFFERENT axis from the R6 tool-authorization
   * budget (`Delegate.timeout` -> `Defaults.declaredToolTimeoutMs`), which is
   * the blocking-tool judgement window. Both exist, they do not interact, and
   * conflating them would silently change the stuck-judgement face.
   */
  def effective(snapshot: JevGateSnapshot): JevGateOutcome =
    if !snapshot.configured then JevGateOutcome.Off(JevGateReason.NotConfigured)
    else if !snapshot.enabled then JevGateOutcome.Off(JevGateReason.ToggleOff)
    else if !KnownProviders.contains(snapshot.provider) then JevGateOutcome.Off(JevGateReason.ProviderMissing)
    else JevGateOutcome.Applied(snapshot)

  /** Convenience predicate for call sites that only branch on apply/not. */
  def isApplied(snapshot: JevGateSnapshot): Boolean = effective(snapshot) match
    case JevGateOutcome.Applied(_) => true
    case _ => false
