package nebflow.core.delegate

import cats.effect.{IO, Resource}
import nebflow.shared.PathUtil
import java.nio.channels.{FileChannel, OverlappingFileLockException}
import java.nio.file.StandardOpenOption

/**
 * Process-independent session-start lease: the single serialization point every
 * session start passes through — fresh spawn bookkeeping, in-place
 * continuation, frozen wake and boot revival all hold the same per-session
 * file lock while they decide who owns the execution window, so two starters
 * can never race one session identity. The lock layout (`workspaces/start-
 * <id>.lock`) predates this object's current name and is kept unchanged so
 * existing on-disk locks keep their meaning. Never starts an actor.
 */
object SessionStartLease:
  // A probe briefly owns the OS lock. Serialize local acquisition with that probe
  // so an observer cannot be mistaken for a competing session starter.
  private val probeGate = new Object

  final class SessionBusy extends IllegalStateException("Session initialization or recovery is already in progress")

  def exclusive[A](id: String)(action: IO[A]): IO[A] =
    Resource
      .make(IO.blocking {
        probeGate.synchronized {
          require(id.matches("[A-Za-z0-9._-]+"), "Invalid session identity")
          val directory = PathUtil.dataRoot / "workspaces"
          os.makeDir.all(directory)
          val channel =
            FileChannel.open((directory / s"start-$id.lock").toNIO, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
          try
            val lock = channel.tryLock()
            if lock == null then throw new SessionBusy
            (channel, lock)
          catch
            case error: OverlappingFileLockException =>
              channel.close()
              throw new SessionBusy
            case error: Throwable => channel.close(); throw error
        }
      }) { case (channel, lock) =>
        IO.blocking {
          try lock.release()
          finally channel.close()
        }
      }
      .use(_ => action)

  /**
   * Start-lease occupancy probe (read-only, never blocks): true when the session's
   * start lock is currently held by anyone (this JVM included — Java file locks are
   * JVM-wide, so a same-process holder shows up as an overlapping-lock refusal).
   * Consumers use it as a "session start/revival is in flight" signal (delegate
   * completion-gate defer; busy checks). Probe failure (IO error) reads as held =
   * conservative side.
   */
  def isHeld(id: String): Boolean = probeGate.synchronized {
    try
      val directory = PathUtil.dataRoot / "workspaces"
      os.makeDir.all(directory)
      val channel =
        FileChannel.open((directory / s"start-$id.lock").toNIO, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
      try
        val lock = channel.tryLock()
        if lock == null then true
        else
          lock.release()
          false
      catch
        case _: OverlappingFileLockException => true
        case _: Throwable => true
      finally channel.close()
    catch case _: Throwable => true
  }

end SessionStartLease
