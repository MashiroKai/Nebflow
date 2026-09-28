package nebflow.core.tools

import cats.effect.{IO, Ref}

import java.nio.file.Path

case class ReadEntry(
  path: Path,
  timestamp: Long,
  isPartialView: Boolean = false
)

class ReadTracker private (state: Ref[IO, Vector[ReadEntry]]):

  /** Record a file read with current timestamp. Updates timestamp if already tracked. */
  def recordRead(path: Path, isPartialView: Boolean = false): IO[Unit] = state.update { entries =>
    val filtered = entries.filterNot(_.path == path)
    filtered :+ ReadEntry(path, System.currentTimeMillis(), isPartialView)
  }

  def clear(): IO[Unit] = state.set(Vector.empty)

  /** Return the N most recently read file paths (most recent first). */
  def recentFiles(n: Int): IO[List[Path]] =
    state.get.map(_.takeRight(n).reverse.map(_.path).toList)

  /** Return all tracked paths (for backward compat). */
  def allPaths: IO[Set[Path]] = state.get.map(_.map(_.path).toSet)

end ReadTracker

object ReadTracker:

  def create: IO[ReadTracker] =
    Ref.of[IO, Vector[ReadEntry]](Vector.empty).map(new ReadTracker(_))
