package nebflow.agent

import org.apache.pekko.actor.typed.{Behavior, ActorRef}
import org.apache.pekko.actor.typed.scaladsl.{Behaviors, TimerScheduler}

import scala.concurrent.duration.*

// ============================================================
// Dream command protocol — public so WriteMemoryTool can reference it
// ============================================================

/** Commands accepted by the Dream scheduler actor. */
sealed trait DreamCommand

object DreamCommand:

  /** A new memory entry from WriteMemoryTool. */
  case class ProcessEntry(
    scope: String,
    content: String,
    detail: Option[String],
    source: String,
    folderId: Option[String]
  ) extends DreamCommand

  /** Debounce timer fired — process accumulated entries. */
  private[nebflow] case object FlushEntries extends DreamCommand

  /** 24-hour full cycle timer fired. */
  private[nebflow] case object FullCycleTick extends DreamCommand

  /** Dream agent finished processing (detected via intercepted Done event). */
  private[nebflow] case object DreamComplete extends DreamCommand

  /** Safety timeout — Dream agent didn't signal completion in time. */
  private[nebflow] case object DreamTimeout extends DreamCommand

  /** Shutdown the scheduler. */
  private[nebflow] case object Shutdown extends DreamCommand
end DreamCommand

/**
 * Dream scheduler state machine — extracted from [[MemoryAgentManager]]
 * for testability.
 *
 * Two states:
 *   - '''idle''': no Dream agent running. Accumulates entries with a debounce
 *     timer. When the timer fires, triggers a Dream cycle and transitions to
 *     `dreaming`.
 *   - '''dreaming''': Dream agent is running. Accumulates new entries in a
 *     buffer '''without killing the agent'''. When the agent signals
 *     completion (`DreamComplete`) or the safety timeout fires
 *     (`DreamTimeout`), stops the old agent and either starts a new cycle
 *     with buffered entries or returns to `idle`.
 *
 * This prevents memory loss: entries arriving while a Dream is in progress
 * are buffered and processed in the next cycle, rather than being lost when
 * the in-progress agent is killed.
 */
object DreamScheduler:

  /** Dependencies the scheduler needs from its host. */
  trait Hooks:
    /** Trigger a Dream cycle with the given entries. Returns true on success. */
    def trigger(entries: List[DreamCommand.ProcessEntry], isFullCycle: Boolean): Boolean

    /** Stop the currently running Dream agent (if any). */
    def stopDreamAgent(): Unit

    /** Called when lastDreamTime should be updated to now. */
    def touchLastDreamTime(): Unit
  end Hooks

  /** Factory: create the scheduler behavior. */
  def apply(
    hooks: Hooks,
    debounceDelay: FiniteDuration = 2.minutes,
    fullCycleInterval: FiniteDuration = 24.hours,
    dreamTimeout: FiniteDuration = 5.minutes
  ): Behavior[DreamCommand] =
    Behaviors.withTimers { timers =>
      timers.startSingleTimer(DreamCommand.FullCycleTick, fullCycleInterval)
      idle(Nil, timers, hooks, debounceDelay, fullCycleInterval, dreamTimeout)
    }

  // ============================================================
  // State: idle — no Dream agent running
  // ============================================================

  private def idle(
    buffer: List[DreamCommand.ProcessEntry],
    timers: TimerScheduler[DreamCommand],
    hooks: Hooks,
    debounceDelay: FiniteDuration,
    fullCycleInterval: FiniteDuration,
    dreamTimeout: FiniteDuration
  ): Behavior[DreamCommand] =
    Behaviors.receiveMessage {
      case entry: DreamCommand.ProcessEntry =>
        val newBuffer = buffer :+ entry
        if buffer.isEmpty then timers.startSingleTimer(DreamCommand.FlushEntries, debounceDelay)
        idle(newBuffer, timers, hooks, debounceDelay, fullCycleInterval, dreamTimeout)

      case DreamCommand.FlushEntries =>
        if buffer.nonEmpty then
          if hooks.trigger(buffer, isFullCycle = false) then
            hooks.touchLastDreamTime()
            timers.startSingleTimer(DreamCommand.DreamTimeout, dreamTimeout)
            dreaming(Nil, pendingFullCycle = false, timers, hooks, debounceDelay, fullCycleInterval, dreamTimeout)
          else idle(Nil, timers, hooks, debounceDelay, fullCycleInterval, dreamTimeout)
        else idle(Nil, timers, hooks, debounceDelay, fullCycleInterval, dreamTimeout)

      case DreamCommand.FullCycleTick =>
        if buffer.nonEmpty then timers.cancel(DreamCommand.FlushEntries)
        val triggered = hooks.trigger(buffer, isFullCycle = true)
        timers.startSingleTimer(DreamCommand.FullCycleTick, fullCycleInterval)
        if triggered then
          hooks.touchLastDreamTime()
          timers.startSingleTimer(DreamCommand.DreamTimeout, dreamTimeout)
          dreaming(Nil, pendingFullCycle = false, timers, hooks, debounceDelay, fullCycleInterval, dreamTimeout)
        else idle(Nil, timers, hooks, debounceDelay, fullCycleInterval, dreamTimeout)

      case DreamCommand.Shutdown =>
        Behaviors.stopped

      // Stale completion signals from a previous cycle — ignore
      case DreamCommand.DreamComplete | DreamCommand.DreamTimeout =>
        Behaviors.same
    }

  // ============================================================
  // State: dreaming — Dream agent is running
  // ============================================================

  private def dreaming(
    buffer: List[DreamCommand.ProcessEntry],
    pendingFullCycle: Boolean,
    timers: TimerScheduler[DreamCommand],
    hooks: Hooks,
    debounceDelay: FiniteDuration,
    fullCycleInterval: FiniteDuration,
    dreamTimeout: FiniteDuration
  ): Behavior[DreamCommand] =
    Behaviors.receiveMessage {
      case entry: DreamCommand.ProcessEntry =>
        dreaming(buffer :+ entry, pendingFullCycle, timers, hooks, debounceDelay, fullCycleInterval, dreamTimeout)

      case DreamCommand.DreamComplete =>
        timers.cancel(DreamCommand.DreamTimeout)
        onDreamFinished(buffer, pendingFullCycle, timers, hooks, debounceDelay, fullCycleInterval, dreamTimeout)

      case DreamCommand.DreamTimeout =>
        timers.cancel(DreamCommand.DreamTimeout)
        onDreamFinished(buffer, pendingFullCycle, timers, hooks, debounceDelay, fullCycleInterval, dreamTimeout)

      // Stale debounce timer from idle — ignore
      case DreamCommand.FlushEntries =>
        Behaviors.same

      // Full cycle fires while Dream running — defer to after completion
      case DreamCommand.FullCycleTick =>
        timers.startSingleTimer(DreamCommand.FullCycleTick, fullCycleInterval)
        dreaming(buffer, pendingFullCycle = true, timers, hooks, debounceDelay, fullCycleInterval, dreamTimeout)

      case DreamCommand.Shutdown =>
        Behaviors.stopped
    }

  // ============================================================
  // Helper: handle Dream completion
  // ============================================================

  private def onDreamFinished(
    buffer: List[DreamCommand.ProcessEntry],
    pendingFullCycle: Boolean,
    timers: TimerScheduler[DreamCommand],
    hooks: Hooks,
    debounceDelay: FiniteDuration,
    fullCycleInterval: FiniteDuration,
    dreamTimeout: FiniteDuration
  ): Behavior[DreamCommand] =
    hooks.stopDreamAgent()

    if buffer.nonEmpty || pendingFullCycle then
      if hooks.trigger(buffer, isFullCycle = pendingFullCycle) then
        if pendingFullCycle then hooks.touchLastDreamTime()
        timers.startSingleTimer(DreamCommand.DreamTimeout, dreamTimeout)
        dreaming(Nil, pendingFullCycle = false, timers, hooks, debounceDelay, fullCycleInterval, dreamTimeout)
      else idle(Nil, timers, hooks, debounceDelay, fullCycleInterval, dreamTimeout)
    else idle(Nil, timers, hooks, debounceDelay, fullCycleInterval, dreamTimeout)
  end onDreamFinished

end DreamScheduler
