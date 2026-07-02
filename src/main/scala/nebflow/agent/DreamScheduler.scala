package nebflow.agent

import cats.effect.IO
import cats.effect.std.{Dispatcher, Queue}
import cats.effect.unsafe.implicits.global

import scala.concurrent.duration.*

// ============================================================
// Dream command protocol — public so WriteMemoryTool can reference it
// ============================================================

/** Commands accepted by the Dream scheduler. */
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

end DreamScheduler

/**
 * IO-based Dream scheduler — replaces the old Pekko typed actor.
 *
 * The two-phase state machine (idle → dreaming) is preserved as recursive IO.
 * Entry submission and completion signals go through a Queue.
 *
 * Timer handling:
 *   - Full cycle timer always runs (in both idle and dreaming states).
 *   - Debounce timer starts on the FIRST entry only — subsequent entries
 *     do NOT restart it. Tracked via a deadline timestamp.
 *   - Dream timeout runs only in dreaming state.
 */
class DreamScheduler(
  hooks: DreamScheduler.Hooks,
  debounceDelay: FiniteDuration = 2.minutes,
  fullCycleInterval: FiniteDuration = 24.hours,
  dreamTimeout: FiniteDuration = 5.minutes
)(using dispatcher: Dispatcher[IO]):

  private val queue: Queue[IO, DreamCommand] =
    dispatcher.unsafeRunSync(Queue.unbounded[IO, DreamCommand])

  /** Submit a new memory entry for Dream processing. */
  def submitEntry(entry: DreamCommand.ProcessEntry): IO[Unit] =
    queue.offer(entry)

  /** Signal that the Dream agent has finished processing. */
  def signalComplete: IO[Unit] =
    queue.offer(DreamCommand.DreamComplete)

  /** Signal that the Dream agent timed out. */
  def signalTimeout: IO[Unit] =
    queue.offer(DreamCommand.DreamTimeout)

  /** Shut down the scheduler loop. */
  def shutdown: IO[Unit] =
    queue.offer(DreamCommand.Shutdown)

  /** Launch the background loop. Call once at startup. */
  def start: IO[Unit] = idleLoop(Nil, None)

  // ============================================================
  // State: idle — no Dream agent running
  // ============================================================

  private def idleLoop(
    buffer: List[DreamCommand.ProcessEntry],
    debounceDeadline: Option[Long]
  ): IO[Unit] =
    val timer: IO[Boolean] =
      if buffer.isEmpty then
        // Only the full cycle timer is active
        IO.sleep(fullCycleInterval).as(true)
      else
        // Debounce timer — sleep until deadline, race with full cycle
        val remaining = debounceDeadline match
          case Some(deadline) =>
            val ms = deadline - System.currentTimeMillis()
            ms.max(1).millis
          case None => debounceDelay
        IO.race(IO.sleep(remaining), IO.sleep(fullCycleInterval)).map {
          case Left(_)  => false // debounce fired
          case Right(_) => true  // full cycle fired
        }

    IO.race(queue.take, timer).flatMap {
      case Left(cmd)           => handleIdle(cmd, buffer, debounceDeadline)
      case Right(isFullCycle)  => flushIdle(buffer, isFullCycle)
    }

  private def handleIdle(
    cmd: DreamCommand,
    buffer: List[DreamCommand.ProcessEntry],
    debounceDeadline: Option[Long]
  ): IO[Unit] =
    cmd match
      case pe: DreamCommand.ProcessEntry =>
        val newBuffer = buffer :+ pe
        // Start debounce only on first entry (when buffer was empty)
        val newDeadline = debounceDeadline.orElse(Some(System.currentTimeMillis() + debounceDelay.toMillis))
        idleLoop(newBuffer, newDeadline)
      case DreamCommand.FlushEntries     => flushIdle(buffer, isFullCycle = false)
      case DreamCommand.FullCycleTick    => flushIdle(buffer, isFullCycle = true)
      // Stale completion signals from a previous cycle — ignore
      case DreamCommand.DreamComplete | DreamCommand.DreamTimeout => idleLoop(buffer, debounceDeadline)
      case DreamCommand.Shutdown => IO.unit

  private def flushIdle(buffer: List[DreamCommand.ProcessEntry], isFullCycle: Boolean): IO[Unit] =
    if buffer.isEmpty && !isFullCycle then idleLoop(Nil, None)
    else if hooks.trigger(buffer, isFullCycle) then
      hooks.touchLastDreamTime()
      dreamingLoop(Nil, pendingFullCycle = false)
    else idleLoop(Nil, None)

  // ============================================================
  // State: dreaming — Dream agent is running
  // ============================================================

  private def dreamingLoop(
    buffer: List[DreamCommand.ProcessEntry],
    pendingFullCycle: Boolean
  ): IO[Unit] =
    val timer: IO[Boolean] =
      IO.race(IO.sleep(dreamTimeout), IO.sleep(fullCycleInterval)).map {
        case Left(_)  => false // dream timeout
        case Right(_) => true  // full cycle fired
      }

    IO.race(queue.take, timer).flatMap {
      case Left(cmd)  => handleDreaming(cmd, buffer, pendingFullCycle)
      case Right(isFullCycle) =>
        if isFullCycle then dreamingLoop(buffer, pendingFullCycle = true)
        else onDreamFinished(buffer, pendingFullCycle) // dream timeout
    }

  private def handleDreaming(
    cmd: DreamCommand,
    buffer: List[DreamCommand.ProcessEntry],
    pendingFullCycle: Boolean
  ): IO[Unit] =
    cmd match
      case pe: DreamCommand.ProcessEntry => dreamingLoop(buffer :+ pe, pendingFullCycle)
      case DreamCommand.DreamComplete    => onDreamFinished(buffer, pendingFullCycle)
      case DreamCommand.DreamTimeout     => onDreamFinished(buffer, pendingFullCycle)
      // Stale debounce timer from idle — ignore
      case DreamCommand.FlushEntries => dreamingLoop(buffer, pendingFullCycle)
      // Full cycle fires while Dream running — defer to after completion
      case DreamCommand.FullCycleTick => dreamingLoop(buffer, pendingFullCycle = true)
      case DreamCommand.Shutdown      => IO.unit

  // ============================================================
  // Helper: handle Dream completion
  // ============================================================

  private def onDreamFinished(
    buffer: List[DreamCommand.ProcessEntry],
    pendingFullCycle: Boolean
  ): IO[Unit] =
    hooks.stopDreamAgent()

    if buffer.nonEmpty || pendingFullCycle then
      if hooks.trigger(buffer, isFullCycle = pendingFullCycle) then
        if pendingFullCycle then hooks.touchLastDreamTime()
        dreamingLoop(Nil, pendingFullCycle = false)
      else idleLoop(Nil, None)
    else idleLoop(Nil, None)

end DreamScheduler
