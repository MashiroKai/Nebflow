package nebflow.core.flow

import nebflow.core.NebflowLogger
import nebflow.core.entity.NodeResult

/**
 * Unified semantics for Flow DAG nodes — single source of truth for
 * node lifecycle status and switch-verdict normalization.
 *
 * Everything else (executor, registry, frontend, persistence) derives from
 * this file; no hand-written status strings or per-flow alias tables.
 */
enum NodeStatus:
  case Pending, Running, Completed, Failed, Cancelled

  /** Stable wire value shared with persistence / WS / frontend (kebab, lowercase). */
  def wire: String = this match
    case Pending => "pending"
    case Running => "running"
    case Completed => "completed"
    case Failed => "failed"
    case Cancelled => "cancelled"

object NodeStatus:

  /** Parse a wire value. Unknown strings decode to None — explicitly exposed, never silent. */
  def fromWire(raw: String): Option[NodeStatus] = raw.trim.toLowerCase match
    case "pending" => Some(Pending)
    case "running" => Some(Running)
    case "completed" => Some(Completed)
    case "failed" => Some(Failed)
    case "cancelled" => Some(Cancelled)
    case _ => None

  /**
   * NodeResult → NodeStatus mapping (the only mapping function).
   * `pending` / `running` are runtime states set by the executor, not
   * derived from NodeResult. `verdict` is a routing value, not lifecycle.
   */
  def toNodeStatus(result: NodeResult, cancelled: Boolean): NodeStatus =
    if cancelled then Cancelled
    else if result.success then Completed
    else Failed

end NodeStatus

/**
 * Engine-level verdict normalization. Flow domain values (pass/fix, merge/skip,
 * merge/reject, pass/revise) stay as declared in flow.json; generic binary
 * outcomes (ok/pass/success/done… vs error/fail/false/reject…) are normalized
 * to the engine-standard `ok` / `error` families so flow.json never needs to
 * declare alias cases.
 */
object VerdictFamily:

  /** Aliases that route to the ok/continue family. */
  val OkFamily = Set(
    "ok",
    "pass",
    "passed",
    "success",
    "succeeded",
    "successful",
    "true",
    "yes",
    "done",
    "complete",
    "completed",
    "clean",
    "fixed"
  )

  /** Aliases that route to the error/stop family. */
  val ErrorFamily = Set(
    "error",
    "fail",
    "failed",
    "failure",
    "false",
    "no",
    "reject",
    "rejected",
    "abort",
    "aborted",
    "revise"
  )

  private val logger = NebflowLogger.forName("nebflow.entity.executor")

  /** trim + lowercase. */
  def normalize(raw: String): String = raw.trim.toLowerCase

  /** Family of a normalized value: Some("ok") | Some("error") | None. */
  def familyOf(raw: String): Option[String] =
    val n = normalize(raw)
    if OkFamily.contains(n) then Some("ok")
    else if ErrorFamily.contains(n) then Some("error")
    else None

  /**
   * Match a raw verdict against the case keys of a switch node.
   *
   * Pipeline: ① exact (case-insensitive) → ② same-family (raw ∈ ok/error
   * family AND cases has exactly one key of that family) → ③ substring
   * containment (warn) → None.
   *
   * Returns the matched case key — for family hits this is a case key that
   * may differ from the raw value (e.g. raw "pass" → case key "ok").
   */
  def matchCase(raw: String, cases: Set[String]): Option[String] =
    val n = normalize(raw)
    if n.isEmpty then None
    else
      // ① exact match (case-insensitive)
      cases.find(c => normalize(c) == n) match
        case Some(hit) => Some(hit)
        case None =>
          // ② same-family match — unambiguous only when cases has exactly one
          //    key of the family (flow.json spec forbids synonym duplicates)
          familyOf(n) match
            case Some(fam) =>
              val famKeys = cases.filter(c => familyOf(c).contains(fam))
              if famKeys.size == 1 then Some(famKeys.head)
              else None
            case None =>
              // ③ substring containment (legacy fallback, warns)
              val hit = cases.find(c => n.contains(normalize(c)) || normalize(c).contains(n))
              hit match
                case Some(h) =>
                  logger.warnSync(
                    s"Verdict '$raw' matched case '$h' by substring — ambiguous; prefer exact values (ok/error/pass/…) from the flow contract"
                  )
                  Some(h)
                case None => None
    end if
  end matchCase

end VerdictFamily
