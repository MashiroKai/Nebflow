package nebflow.core.tools

import cats.effect.{IO, Ref}

import java.nio.file.Path

class ReadTracker private (state: Ref[IO, Vector[(Path, Long)]]):

  /** Record a file read with current timestamp. Updates timestamp if already tracked. */
  def recordRead(path: Path): IO[Unit] = state.update { entries =>
    val filtered = entries.filterNot(_._1 == path)
    filtered :+ (path -> System.currentTimeMillis())
  }

  def hasBeenRead(path: Path): IO[Boolean] = state.get.map(_.exists(_._1 == path))

  def clear(): IO[Unit] = state.set(Vector.empty)

  /** Return the N most recently read file paths (most recent first). */
  def recentFiles(n: Int): IO[List[Path]] =
    state.get.map(_.takeRight(n).reverse.map(_._1).toList)

  /** Return all tracked paths (for backward compat). */
  def allPaths: IO[Set[Path]] = state.get.map(_.map(_._1).toSet)

object ReadTracker:

  def create: IO[ReadTracker] =
    Ref.of[IO, Vector[(Path, Long)]](Vector.empty).map(new ReadTracker(_))
