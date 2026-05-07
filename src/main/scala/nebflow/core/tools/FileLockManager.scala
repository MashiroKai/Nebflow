package nebflow.core.tools

import cats.effect.std.Semaphore
import cats.effect.{IO, Ref}

import java.nio.file.Path

/**
 * Per-file write lock shared by all agents.
 *
 * Each file path maps to a binary semaphore (permit count = 1).
 * WriteTool and EditTool wrap their write section in `withWriteLock`
 * so that concurrent agents writing the same file are serialized.
 * The second agent will see an mtime mismatch after acquiring the
 * lock and fail with "file modified externally", prompting a re-read.
 */
class FileLockManager private (locks: Ref[IO, Map[Path, Semaphore[IO]]]):

  def withWriteLock[A](path: Path)(fa: IO[A]): IO[A] =
    for
      sem <- getOrCreate(path)
      a <- sem.permit.use(_ => fa)
    yield a

  private def getOrCreate(path: Path): IO[Semaphore[IO]] =
    // Speculatively create, then atomically check-and-insert.
    // If another fiber won the race, we use theirs and discard ours.
    for
      sem <- Semaphore[IO](1)
      existing <- locks.modify { m =>
        m.get(path) match
          case Some(existing) => (m, Some(existing))
          case None => (m.updated(path, sem), None)
      }
    yield existing.getOrElse(sem)

object FileLockManager:

  def create: IO[FileLockManager] =
    Ref.of[IO, Map[Path, Semaphore[IO]]](Map.empty).map(new FileLockManager(_))
