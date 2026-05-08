package nebflow.core.hooks

import io.circe.JsonObject

// ============================================================
// Hook Event Types
// ============================================================

/** Event group — determines whether matcher is used. */
enum HookEventGroup:
  case Tool, Compact, Lifecycle

/**
 * Hook event types.
 * Adding a new event: 1) add enum value here 2) call hookEngine.emit() at the trigger point.
 * Everything else (config, engine, protocol) requires zero changes.
 */
enum HookEvent(val group: HookEventGroup, val hasMatcher: Boolean):
  case PreToolUse         extends HookEvent(HookEventGroup.Tool, true)
  case PostToolUse        extends HookEvent(HookEventGroup.Tool, true)
  case PostToolUseFailure extends HookEvent(HookEventGroup.Tool, true)
  case PreCompact         extends HookEvent(HookEventGroup.Compact, false)
  case PostCompact        extends HookEvent(HookEventGroup.Compact, false)
  case SessionStart       extends HookEvent(HookEventGroup.Lifecycle, false)
  case SessionEnd         extends HookEvent(HookEventGroup.Lifecycle, false)
  case Stop               extends HookEvent(HookEventGroup.Lifecycle, false)

object HookEvent:
  /** Parse from config key string, e.g. "PreToolUse". */
  def fromString(s: String): Option[HookEvent] = values.find(_.toString == s)

// ============================================================
// Hook Payload — event-specific data
// ============================================================

/** Data carried by a hook event. Fields are filled based on event type. */
case class HookPayload(
  toolName: Option[String] = None,
  toolInput: Option[JsonObject] = None,
  toolOutput: Option[String] = None,
  toolSuccess: Option[Boolean] = None,
  compactMessagesBefore: Option[Int] = None,
  compactMessagesAfter: Option[Int] = None,
  compactTokensSaved: Option[Long] = None,
  extra: JsonObject = JsonObject.empty
)

// ============================================================
// Hook Config types
// ============================================================

/** A single hook definition (one command to run). */
case class HookDef(
  `type`: String,               // "command" (Phase 1 only; future: "prompt", "http")
  command: String,              // shell command template
  timeout: Int = 60,            // seconds
  continueOnError: Boolean = true
)

/** A matcher + its hooks. matcher is only used for Tool events. */
case class HookRule(
  matcher: String,              // e.g. "Edit|Write", "*", "Bash"
  hooks: List[HookDef]
)

/** Top-level hooks config parsed from nebflow.json. */
case class HooksConfig(
  hooks: Map[String, List[HookRule]]  // key = event name string, e.g. "PreToolUse"
)

object HooksConfig:
  val empty: HooksConfig = HooksConfig(Map.empty)

// ============================================================
// Hook Result — what a hook returns
// ============================================================

enum HookDecision:
  case Allow, Block

/** Aggregated result from executing all hooks for an event. */
case class HookResult(
  decision: HookDecision = HookDecision.Allow,
  reason: Option[String] = None,
  updatedInput: Option[JsonObject] = None,
  additionalContext: Option[String] = None,
  shouldStop: Boolean = false,
  stopReason: Option[String] = None
)

object HookResult:
  val allow: HookResult = HookResult()

  def block(reason: String): HookResult =
    HookResult(decision = HookDecision.Block, reason = Some(reason))

  /** Merge two results: block wins, contexts are concatenated. */
  def merge(a: HookResult, b: HookResult): HookResult =
    HookResult(
      decision = if a.decision == HookDecision.Block || b.decision == HookDecision.Block
        then HookDecision.Block else HookDecision.Allow,
      reason = (a.reason, b.reason) match
        case (Some(ra), Some(rb)) => Some(s"$ra; $rb")
        case (Some(r), None) => Some(r)
        case (None, Some(r)) => Some(r)
        case _ => None,
      updatedInput = b.updatedInput.orElse(a.updatedInput),  // last wins
      additionalContext = (a.additionalContext, b.additionalContext) match
        case (Some(ca), Some(cb)) => Some(s"$ca\n$cb")
        case (Some(c), None) => Some(c)
        case (None, Some(c)) => Some(c)
        case _ => None,
      shouldStop = a.shouldStop || b.shouldStop,
      stopReason = b.stopReason.orElse(a.stopReason)
    )

// ============================================================
// Hook execution context
// ============================================================

case class HookContext(
  sessionId: Option[String],
  projectRoot: String,
  cwd: String
)
