package nebflow.core.hooks

import cats.effect.IO
import cats.syntax.all.*
import io.circe.JsonObject
import nebflow.core.NebflowLogger

/**
 * Hook engine — the core execution orchestrator.
 *
 * All events go through the single `emit()` entry point. The engine does
 * name-based config lookup, matcher filtering, parallel execution, and
 * result aggregation. Adding new event types requires zero changes here.
 */
trait HookEngine:

  /**
   * Universal trigger — the only method that matters.
   *
   * @param event    Hook event type
   * @param payload  Event-specific data
   * @param context  Execution context
   * @param toolName Tool name (for matcher filtering on Tool events)
   * @return Aggregated HookResult
   */
  def emit(
    event: HookEvent,
    payload: HookPayload,
    context: HookContext,
    toolName: Option[String] = None
  ): IO[HookResult]

  // Convenience methods — type-safe wrappers

  final def beforeTool(toolName: String, toolInput: JsonObject, context: HookContext): IO[HookResult] =
    emit(
      HookEvent.PreToolUse,
      HookPayload(
        toolName = Some(toolName),
        toolInput = Some(toolInput)
      ),
      context,
      Some(toolName)
    )

  final def afterTool(
    toolName: String,
    toolInput: JsonObject,
    toolOutput: String,
    success: Boolean,
    context: HookContext
  ): IO[HookResult] =
    emit(
      HookEvent.PostToolUse,
      HookPayload(
        toolName = Some(toolName),
        toolInput = Some(toolInput),
        toolOutput = Some(toolOutput),
        toolSuccess = Some(success)
      ),
      context,
      Some(toolName)
    )

  final def afterToolFailure(
    toolName: String,
    toolInput: JsonObject,
    toolOutput: String,
    context: HookContext
  ): IO[HookResult] =
    emit(
      HookEvent.PostToolUseFailure,
      HookPayload(
        toolName = Some(toolName),
        toolInput = Some(toolInput),
        toolOutput = Some(toolOutput),
        toolSuccess = Some(false)
      ),
      context,
      Some(toolName)
    )

  final def beforeCompact(messagesBefore: Int, context: HookContext): IO[HookResult] =
    emit(
      HookEvent.PreCompact,
      HookPayload(
        compactMessagesBefore = Some(messagesBefore)
      ),
      context
    )

  final def afterCompact(
    messagesBefore: Int,
    messagesAfter: Int,
    tokensSaved: Long,
    context: HookContext
  ): IO[HookResult] =
    emit(
      HookEvent.PostCompact,
      HookPayload(
        compactMessagesBefore = Some(messagesBefore),
        compactMessagesAfter = Some(messagesAfter),
        compactTokensSaved = Some(tokensSaved)
      ),
      context
    )

  final def onSessionStart(context: HookContext): IO[HookResult] =
    emit(HookEvent.SessionStart, HookPayload(), context)

  final def onSessionEnd(context: HookContext): IO[Unit] =
    emit(HookEvent.SessionEnd, HookPayload(), context).void

  final def onStop(context: HookContext): IO[Unit] =
    emit(HookEvent.Stop, HookPayload(), context).void

end HookEngine

// ============================================================
// Live implementation
// ============================================================

object HookEngine:

  private val logger = NebflowLogger.forName("nebflow.hooks")

  def apply(config: HooksConfig): HookEngine = new HookEngine:

    def emit(
      event: HookEvent,
      payload: HookPayload,
      context: HookContext,
      toolName: Option[String] = None
    ): IO[HookResult] =
      config.hooks.get(event.toString) match
        case None | Some(Nil) => IO.pure(HookResult.allow) // no hooks configured for this event
        case Some(rules) =>
          // Filter by matcher (Tool events) or select all (non-Tool events)
          val matched =
            if event.hasMatcher then rules.filter(r => HookMatcher.matches(r.matcher, toolName.getOrElse("")))
            else rules // Compact/Lifecycle events fire all rules

          if matched.isEmpty then IO.pure(HookResult.allow)
          else
            logger.debug(s"Hook $event: ${matched.map(_.matcher).mkString(", ")} matched, executing...")
            executeHooks(matched, event, payload, context, toolName)

    private def executeHooks(
      rules: List[HookRule],
      event: HookEvent,
      payload: HookPayload,
      context: HookContext,
      toolName: Option[String]
    ): IO[HookResult] =
      // Collect all hook defs from all matched rules, execute in parallel
      val allDefs = rules.flatMap(_.hooks)
      if allDefs.isEmpty then IO.pure(HookResult.allow)
      else
        allDefs
          .parTraverse { defn =>
            HookRunner.run(defn, event, payload, context, toolName)
          }
          .map { results =>
            results.foldLeft(HookResult.allow)(HookResult.merge)
          }

    end executeHooks

  /** No-op engine — used when no hooks are configured. */
  val noop: HookEngine = new HookEngine:
    def emit(event: HookEvent, payload: HookPayload, context: HookContext, toolName: Option[String]): IO[HookResult] =
      IO.pure(HookResult.allow)
end HookEngine
