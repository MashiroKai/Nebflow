package nebflow.core.delegate

import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

import scala.util.Try

/** Shared by applications using different data roots but the same git common-dir. */
object RepositoryWriteLock:

  /**
   * A held cross-process write lock on one repository. The OS file lock lives on
   * `<git-common-dir>/stand-workspace-write.lock`; releasing it restores write
   * availability for every process sharing the repository.
   */
  final case class Handle private[RepositoryWriteLock] (channel: FileChannel, lock: java.nio.channels.FileLock):

    def release(): Unit =
      Try {
        lock.release()
        channel.close()
      }

  /** Acquire without blocking: Left = another writer (any process) holds the repository. */
  def acquire(repository: String): Either[String, Handle] =
    Try {
      val directory = java.nio.file.Path.of(repository).toRealPath()
      val channel = FileChannel.open(
        directory.resolve("stand-workspace-write.lock"),
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE
      )
      val lock =
        try channel.tryLock()
        catch
          case e: Throwable =>
            channel.close()
            throw e
      if lock == null then
        channel.close()
        throw new IllegalStateException("Repository is in use by another workspace operation")
      Handle(channel, lock)
    }.toEither.left.map {
      case _: java.nio.channels.OverlappingFileLockException =>
        "Repository is in use by another workspace operation"
      case e => Option(e.getMessage).filter(_.nonEmpty).getOrElse("Repository is in use")
    }

  def withLock[A](repository: String)(action: => A): Either[String, A] =
    acquire(repository) match
      case Right(handle) =>
        try
          Try(action).toEither.left
            .map(e => Option(e.getMessage).filter(_.nonEmpty).getOrElse("Repository is in use"))
        finally handle.release()
      case Left(error) => Left(error)

  /**
   * Session-reentrant hold above the file lock. The same holder (a session id)
   * may nest acquisitions — a merger running a background job and a foreground
   * command in parallel must not deadlock against itself — while distinct
   * holders in this JVM and every other process remain mutually exclusive
   * (file lock semantics). Every acquisition returns a Hold whose
   * [[releaseFor]] must be called exactly once per acquisition; the underlying
   * file lock is released when the holder's depth reaches zero.
   */
  final case class Hold private[RepositoryWriteLock] (
    holder: String,
    repository: String,
    handle: Handle,
    depth: java.util.concurrent.atomic.AtomicInteger
  )

  private val holds = new java.util.concurrent.ConcurrentHashMap[String, Hold]()

  private def acquireOnce(holder: String, repository: String): Either[String, Hold] =
    Option(holds.get(holder)) match
      case Some(existing) =>
        if existing.repository == repository then
          existing.depth.incrementAndGet()
          Right(existing)
        else Left(s"Session '$holder' already holds the write lock of another repository")
      case None =>
        acquire(repository).flatMap { handle =>
          val candidate = Hold(holder, repository, handle, new java.util.concurrent.atomic.AtomicInteger(1))
          Option(holds.putIfAbsent(holder, candidate)) match
            case None => Right(candidate)
            case Some(winner) =>
              handle.release()
              if winner.repository == repository then
                winner.depth.incrementAndGet()
                Right(winner)
              else Left(s"Session '$holder' already holds the write lock of another repository")
        }

  def acquireFor(holder: String, repository: String): Either[String, Hold] =
    if holder.trim.isEmpty then
      acquire(repository).map(hold =>
        Hold("anonymous", repository, hold, new java.util.concurrent.atomic.AtomicInteger(1))
      )
    else
      Try(acquireOnce(holder, repository)).toEither.left
        .map(e => Option(e.getMessage).filter(_.nonEmpty).getOrElse("Repository is in use"))
        .flatMap(identity)

  /** Release one acquisition. The file lock closes when the holder's depth reaches zero. */
  def releaseFor(hold: Hold): Unit =
    if hold.depth.decrementAndGet() <= 0 then
      holds.remove(hold.holder, hold)
      hold.handle.release()

end RepositoryWriteLock
